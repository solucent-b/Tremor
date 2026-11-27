package dev.tremor.cache;

import dev.tremor.bytecode.ApiModel;
import dev.tremor.bytecode.ApiReader;
import dev.tremor.scan.ScanResult;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Caches parsed API models and scan results on disk, keyed by the SHA-256 of the jar's content,
 * so a second run on the same dependencies skips almost all bytecode reading.
 *
 * <p>Entries are Java-serialized, gzipped, and only ever read back with a filter that allows
 * Tremor's own model classes and basic JDK types. A corrupt or outdated entry is ignored and rebuilt.
 */
public final class AnalysisCache {

    /** Bump when the cached classes change shape, so old entries are ignored. */
    static final int FORMAT = 1;

    private static final ObjectInputFilter FILTER = ObjectInputFilter.Config.createFilter(
            "maxdepth=64;dev.tremor.**;java.lang.*;java.util.*;java.util.ImmutableCollections$*;"
                    + "java.lang.Enum;!*");

    private final Path dir;
    private final Map<Path, String> hashes = new ConcurrentHashMap<>();

    private AnalysisCache(Path dir) {
        this.dir = dir;
    }

    /** A cache stored under {@code cacheRoot/analysis}. */
    public static AnalysisCache in(Path cacheRoot) {
        return new AnalysisCache(cacheRoot.resolve("analysis").resolve("v" + FORMAT));
    }

    /** A cache that never stores anything. */
    public static AnalysisCache disabled() {
        return new AnalysisCache(null);
    }

    /** The API model of a jar, read once and then served from the cache. */
    public ApiModel apiModel(Path jar) {
        if (dir == null || Files.isDirectory(jar)) {
            return ApiReader.read(jar);
        }
        return cached("api", sha256(jar), ApiModel.class, () -> ApiReader.read(jar));
    }

    /**
     * A scan result for a jar, for usages of a particular library version.
     *
     * @param targetKey identifies what was looked for (e.g. the hash of the old library jar)
     */
    public ScanResult scan(Path jar, String targetKey, Supplier<ScanResult> compute) {
        if (dir == null || Files.isDirectory(jar)) {
            return compute.get();
        }
        return cached("scan", sha256(jar) + "-" + targetKey, ScanResult.class, compute);
    }

    /** SHA-256 of a file's content, memoised per path for this run. */
    public String sha256(Path file) {
        return hashes.computeIfAbsent(file.toAbsolutePath(), AnalysisCache::hash);
    }

    private <T> T cached(String kind, String key, Class<T> type, Supplier<T> compute) {
        Path file = dir.resolve(kind).resolve(key.substring(0, 2)).resolve(key + ".bin.gz");
        if (Files.isRegularFile(file)) {
            try (ObjectInputStream in = new ObjectInputStream(
                    new GZIPInputStream(new BufferedInputStream(Files.newInputStream(file))))) {
                in.setObjectInputFilter(FILTER);
                Object value = in.readObject();
                if (type.isInstance(value)) {
                    return type.cast(value);
                }
            } catch (IOException | ClassNotFoundException | RuntimeException e) {
                // Corrupt or from an older Tremor: rebuild below.
            }
        }
        T value = compute.get();
        write(file, value);
        return value;
    }

    private static void write(Path file, Object value) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = Files.createTempFile(file.getParent(), "entry", ".tmp");
            try (OutputStream raw = Files.newOutputStream(tmp);
                    ObjectOutputStream out = new ObjectOutputStream(
                            new GZIPOutputStream(new BufferedOutputStream(raw)))) {
                out.writeObject(value);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // The cache is an optimisation only; never fail the analysis because of it.
        }
    }

    private static String hash(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1 << 16];
            int n;
            while ((n = in.read(buffer)) > 0) {
                digest.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + file + ": " + e.getMessage(), e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
