package dev.tremor.project;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the parts of a Gradle version catalog ({@code gradle/libs.versions.toml}) that Tremor
 * needs: {@code [versions]} and {@code [libraries]}. Bundles and plugins are ignored.
 */
public final class VersionCatalog {

    private static final Pattern ENTRY = Pattern.compile("^\\s*([A-Za-z0-9_.-]+)\\s*=\\s*(.+?)\\s*$");
    private static final Pattern QUOTED = Pattern.compile("^\"([^\"]*)\"$|^'([^']*)'$");
    private static final Pattern FIELD = Pattern.compile(
            "([A-Za-z.]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|\\{\\s*ref\\s*=\\s*\"([^\"]*)\"\\s*})");

    private final Map<String, String> versions = new HashMap<>();
    private final Map<String, String> libraries = new HashMap<>(); // accessor -> g:a[:v]

    private VersionCatalog() {
    }

    public static VersionCatalog empty() {
        return new VersionCatalog();
    }

    public static VersionCatalog read(Path toml) {
        VersionCatalog catalog = new VersionCatalog();
        if (!Files.isRegularFile(toml)) {
            return catalog;
        }
        try {
            String section = "";
            for (String raw : Files.readAllLines(toml)) {
                String line = raw.replaceAll("\\s+#.*$", "").strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("[")) {
                    section = line.replaceAll("[\\[\\]\\s]", "");
                    continue;
                }
                Matcher m = ENTRY.matcher(line);
                if (!m.matches()) {
                    continue;
                }
                if (section.equals("versions")) {
                    catalog.versions.put(m.group(1), unquote(m.group(2)));
                } else if (section.equals("libraries")) {
                    catalog.libraries.put(accessor(m.group(1)), m.group(2));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return catalog;
    }

    /**
     * Resolves {@code libs.foo.bar} (accessor {@code foo.bar}) to {@code group:artifact[:version]}.
     */
    public Optional<String> library(String accessor) {
        String raw = libraries.get(accessor);
        if (raw == null) {
            return Optional.empty();
        }
        String simple = unquote(raw);
        if (!simple.startsWith("{")) {
            return Optional.of(simple);
        }
        Map<String, String> fields = new HashMap<>();
        Matcher f = FIELD.matcher(raw);
        while (f.find()) {
            String value = f.group(2) != null ? f.group(2) : f.group(3) != null ? f.group(3) : f.group(4);
            String key = f.group(4) != null && f.group(1).equals("version") ? "version.ref" : f.group(1);
            fields.put(key, value);
        }
        String module = fields.containsKey("module") ? fields.get("module")
                : fields.get("group") + ":" + fields.get("name");
        String version = fields.containsKey("version.ref") ? versions.get(fields.get("version.ref"))
                : fields.get("version");
        return Optional.of(version == null ? module : module + ":" + version);
    }

    /** Gradle turns {@code jackson-databind} into the accessor {@code libs.jackson.databind}. */
    static String accessor(String alias) {
        return alias.replace('-', '.').replace('_', '.').toLowerCase(Locale.ROOT);
    }

    private static String unquote(String s) {
        Matcher m = QUOTED.matcher(s.strip());
        if (m.matches()) {
            return m.group(1) != null ? m.group(1) : m.group(2);
        }
        return s.strip();
    }
}
