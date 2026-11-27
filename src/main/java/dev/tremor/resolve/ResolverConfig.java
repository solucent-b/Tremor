package dev.tremor.resolve;

import java.nio.file.Path;
import java.util.List;

/**
 * Where Tremor caches downloads and which remote repositories it reads from.
 *
 * @param cacheDir Tremor's cache root; the Maven local repository lives in {@code cacheDir/repository}
 * @param repositoryUrls remote repositories, searched in order
 */
public record ResolverConfig(Path cacheDir, List<String> repositoryUrls) {

    public static final String MAVEN_CENTRAL = "https://repo.maven.apache.org/maven2/";

    public ResolverConfig {
        repositoryUrls = List.copyOf(repositoryUrls);
    }

    /** Default cache ({@code ~/.tremor/cache}, or {@code $TREMOR_CACHE}) and Maven Central. */
    public static ResolverConfig defaults() {
        return new ResolverConfig(defaultCacheDir(), List.of(MAVEN_CENTRAL));
    }

    public static Path defaultCacheDir() {
        String override = System.getenv("TREMOR_CACHE");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        return Path.of(System.getProperty("user.home"), ".tremor", "cache");
    }

    public ResolverConfig withExtraRepositories(List<String> urls) {
        List<String> all = new java.util.ArrayList<>(urls);
        all.addAll(repositoryUrls);
        return new ResolverConfig(cacheDir, all);
    }

    public Path localRepository() {
        return cacheDir.resolve("repository");
    }
}
