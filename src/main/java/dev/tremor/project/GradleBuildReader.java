package dev.tremor.project;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads dependencies from a Gradle build <em>without running Gradle</em>: {@code build.gradle} or
 * {@code build.gradle.kts}, {@code gradle.properties}, and {@code gradle/libs.versions.toml}.
 *
 * <p>Understands the common forms: {@code implementation 'g:a:v'}, {@code implementation("g:a:v")},
 * {@code implementation group: 'g', name: 'a', version: 'v'}, {@code implementation(libs.foo)},
 * {@code platform(...)} BOMs, and {@code $var} / {@code ${var}} versions defined in the build file
 * or {@code gradle.properties}. Anything computed by code (loops, plugins, convention plugins in
 * {@code buildSrc}) is not seen; see the README.
 */
public final class GradleBuildReader {

    private static final Map<String, String> CONFIGURATIONS = Map.ofEntries(
            Map.entry("implementation", "compile"), Map.entry("api", "compile"),
            Map.entry("compile", "compile"), Map.entry("compileOnly", "provided"),
            Map.entry("compileOnlyApi", "provided"), Map.entry("runtimeOnly", "runtime"),
            Map.entry("runtime", "runtime"), Map.entry("testImplementation", "test"),
            Map.entry("testCompileOnly", "test"), Map.entry("testRuntimeOnly", "test"),
            Map.entry("testCompile", "test"), Map.entry("testRuntime", "test"));

    private static final String CONF = "(" + String.join("|", CONFIGURATIONS.keySet()) + ")";
    private static final Pattern STRING_DEP = Pattern.compile(
            "\\b" + CONF + "\\s*\\(?\\s*(platform|enforcedPlatform)?\\s*\\(?\\s*[\"']([^\"'\\s]+)[\"']"
                    + "(?:\\s*\\+\\s*([A-Za-z_][A-Za-z0-9_.]*))?");
    private static final Pattern MAP_DEP = Pattern.compile("\\b" + CONF + "\\s*\\(?\\s*group\\s*[:=]\\s*[\"']([^\"']+)"
            + "[\"']\\s*,\\s*name\\s*[:=]\\s*[\"']([^\"']+)[\"'](?:\\s*,\\s*version\\s*[:=]\\s*[\"']([^\"']+)[\"'])?");
    private static final Pattern CATALOG_DEP = Pattern.compile(
            "\\b" + CONF + "\\s*\\(?\\s*(platform|enforcedPlatform)?\\s*\\(?\\s*libs\\.([A-Za-z0-9_.]+)");
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?:^|[\\s{;])(?:def|val|var|set\\()?\\s*\\(?[\"']?([A-Za-z_][A-Za-z0-9_.]*)[\"']?\\s*[=,]\\s*[\"']([^\"'$]+)[\"']");
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{?([A-Za-z_][A-Za-z0-9_.]*)}?");

    /** True if the directory has a Gradle build file. */
    public static boolean isGradleProject(Path dir) {
        return buildFile(dir).isPresent();
    }

    public ProjectModel read(Path dir) {
        Path build = buildFile(dir).orElseThrow(() -> new ProjectReadException(
                "No pom.xml, build.gradle or build.gradle.kts found in " + dir.toAbsolutePath().normalize()));
        String text;
        try {
            text = stripComments(Files.readString(build));
        } catch (IOException e) {
            throw new ProjectReadException("Could not read " + build + ": " + e.getMessage(), e);
        }
        Map<String, String> vars = variables(dir, text);
        VersionCatalog catalog = VersionCatalog.read(catalogFile(dir));
        List<String> warnings = new ArrayList<>();
        Map<String, DeclaredDependency> deps = new LinkedHashMap<>();
        List<DeclaredDependency> platforms = new ArrayList<>();

        Matcher m = STRING_DEP.matcher(text);
        while (m.find()) {
            // 'group:artifact:' + someVersion (Groovy string concatenation)
            String coords = m.group(4) == null ? m.group(3) : m.group(3) + "${" + m.group(4) + "}";
            add(m.group(1), m.group(2) != null, interpolate(coords, vars), deps, platforms, warnings);
        }
        m = MAP_DEP.matcher(text);
        while (m.find()) {
            String coords = m.group(2) + ":" + m.group(3) + (m.group(4) != null ? ":" + m.group(4) : "");
            add(m.group(1), false, interpolate(coords, vars), deps, platforms, warnings);
        }
        m = CATALOG_DEP.matcher(text);
        while (m.find()) {
            String accessor = m.group(3).replaceAll("\\.get$", "").toLowerCase(java.util.Locale.ROOT);
            Optional<String> coords = catalog.library(accessor);
            if (coords.isPresent()) {
                add(m.group(1), m.group(2) != null, coords.get(), deps, platforms, warnings);
            } else if (!accessor.startsWith("versions.") && !accessor.startsWith("plugins.")
                    && !accessor.startsWith("bundles.")) {
                warnings.add("Could not find libs." + accessor + " in gradle/libs.versions.toml.");
            }
        }
        if (text.contains("libs.bundles.")) {
            warnings.add("Version catalog bundles (libs.bundles.*) are not read yet.");
        }

        warnings.removeIf(w -> w.startsWith("Could not work out the version of ") && deps.values().stream()
                .anyMatch(d -> d.version() != null && w.contains(" " + d.key() + " ")));
        Gav gav = new Gav(vars.getOrDefault("group", "unknown"), projectName(dir),
                vars.getOrDefault("version", "unspecified"));
        List<DeclaredDependency> managed = new ArrayList<>(platforms);
        return new ProjectModel(build.toAbsolutePath().normalize(), gav, null, List.copyOf(deps.values()),
                List.copyOf(managed), List.copyOf(warnings));
    }

    private static void add(String configuration, boolean platform, String coords,
            Map<String, DeclaredDependency> deps, List<DeclaredDependency> platforms, List<String> warnings) {
        String[] p = coords.split(":");
        if (p.length < 2) {
            return;
        }
        String version = p.length > 2 && !p[2].isBlank() ? p[2] : null;
        if (version != null && (version.contains("$") || version.contains("+") || version.contains("["))) {
            warnings.add("Could not work out the version of " + p[0] + ":" + p[1] + " ('" + version + "').");
            version = null;
        }
        String classifier = p.length > 3 ? p[3] : null;
        if (platform) {
            platforms.add(new DeclaredDependency(p[0], p[1], version, "import", "pom", null, false));
            return;
        }
        String scope = CONFIGURATIONS.getOrDefault(configuration, "compile");
        DeclaredDependency d = new DeclaredDependency(p[0], p[1], version, scope, "jar", classifier, false);
        // A later declaration with a known version beats an earlier one without.
        deps.merge(d.key() + (classifier == null ? "" : ":" + classifier), d,
                (old, neu) -> old.version() == null ? neu : old);
        if (version == null) {
            warnings.add("Could not work out the version of " + d.key() + " (it may come from a platform/BOM).");
        }
    }

    private static Map<String, String> variables(Path dir, String text) {
        Map<String, String> vars = new HashMap<>();
        Path props = dir.resolve("gradle.properties");
        if (Files.isRegularFile(props)) {
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(props)) {
                p.load(in);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            p.forEach((k, v) -> vars.put(k.toString(), v.toString().strip()));
        }
        Matcher m = ASSIGNMENT.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            vars.putIfAbsent(name.startsWith("ext.") ? name.substring(4) : name, m.group(2));
        }
        return vars;
    }

    private static String interpolate(String s, Map<String, String> vars) {
        Matcher m = VARIABLE.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = vars.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(value != null ? value : m.group(0)));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static Optional<Path> buildFile(Path dir) {
        for (String name : List.of("build.gradle.kts", "build.gradle")) {
            Path f = dir.resolve(name);
            if (Files.isRegularFile(f)) {
                return Optional.of(f);
            }
        }
        return Optional.empty();
    }

    private static Path catalogFile(Path dir) {
        Path here = dir.resolve("gradle/libs.versions.toml");
        if (Files.isRegularFile(here) || dir.toAbsolutePath().getParent() == null) {
            return here;
        }
        // Sub-projects use the catalog of the root build.
        Path parent = dir.toAbsolutePath().getParent().resolve("gradle/libs.versions.toml");
        return Files.isRegularFile(parent) ? parent : here;
    }

    private static String projectName(Path dir) {
        for (String name : List.of("settings.gradle.kts", "settings.gradle")) {
            Path f = dir.resolve(name);
            if (Files.isRegularFile(f)) {
                try {
                    Matcher m = Pattern.compile("rootProject\\.name\\s*=\\s*[\"']([^\"']+)[\"']")
                            .matcher(Files.readString(f));
                    if (m.find()) {
                        return m.group(1);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        return dir.toAbsolutePath().normalize().getFileName().toString();
    }

    private static String stripComments(String text) {
        return text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)^\\s*//.*$", "")
                .replaceAll("(?m)\\s//[^\"'\\n]*$", "");
    }
}
