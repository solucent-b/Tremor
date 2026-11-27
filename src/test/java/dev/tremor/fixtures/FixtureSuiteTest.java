package dev.tremor.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tremor.analysis.CheckResult;
import dev.tremor.analysis.Finding;
import dev.tremor.analysis.UpgradeChecker;
import dev.tremor.project.Gav;
import dev.tremor.resolve.MavenResolver;
import dev.tremor.resolve.ResolverConfig;
import dev.tremor.testing.Fixture;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * End-to-end tests driven by {@code src/test/resources/fixtures/<name>/}:
 *
 * <ul>
 *   <li>{@code v1/} and {@code v2/}: sources of the fixture library ({@code org.fixture:<name>});</li>
 *   <li>{@code dependents/<artifactId>/}: optional other libraries, built against v1, that the app also uses;</li>
 *   <li>{@code app/}: sources of the project, compiled against v1;</li>
 *   <li>{@code expected.txt}: one line per expected finding, as
 *       {@code <break|warning> <KIND> <origin> | <sentence>}, and optional
 *       {@code version-change <RISK> <library artifactId>} lines.</li>
 * </ul>
 */
class FixtureSuiteTest {

    @TempDir
    Path temp;

    static Stream<String> fixtures() throws IOException, URISyntaxException {
        Path root = Path.of(FixtureSuiteTest.class.getResource("/fixtures").toURI());
        try (Stream<Path> dirs = Files.list(root)) {
            return dirs.filter(Files::isDirectory).map(p -> p.getFileName().toString()).sorted().toList().stream();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void fixture(String name) throws Exception {
        Path dir = Path.of(FixtureSuiteTest.class.getResource("/fixtures/" + name).toURI());
        Fixture fixture = new Fixture(temp);
        Gav v1 = new Gav("org.fixture", name, "1.0");
        Path v1Jar = fixture.library(v1, sources(dir.resolve("v1")));
        fixture.library(new Gav("org.fixture", name, "2.0"), sources(dir.resolve("v2")));

        List<String> appDeps = new ArrayList<>(List.of(v1.groupId() + ":" + v1.artifactId() + ":1.0"));
        List<Path> appClasspath = new ArrayList<>(List.of(v1Jar));
        Path dependents = dir.resolve("dependents");
        if (Files.isDirectory(dependents)) {
            try (Stream<Path> libs = Files.list(dependents)) {
                for (Path lib : libs.sorted().toList()) {
                    Gav gav = new Gav("org.fixture", lib.getFileName().toString(), "1.0");
                    appClasspath.add(fixture.library(gav, List.of(v1Jar), List.of(v1 + ""), sources(lib)));
                    appDeps.add(gav.toString());
                }
            }
        }
        Path project = fixture.project("app", appDeps, appClasspath, sources(dir.resolve("app")));

        CheckResult result;
        try (MavenResolver resolver = new MavenResolver(
                new ResolverConfig(fixture.cacheDir(), List.of(fixture.repo().url())))) {
            result = new UpgradeChecker(resolver).check(project, "org.fixture", name, "2.0");
        }

        List<String> expected = Files.readAllLines(dir.resolve("expected.txt")).stream()
                .map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("#")).toList();
        List<String> actual = new ArrayList<>();
        result.findings().stream().map(FixtureSuiteTest::describe).forEach(actual::add);
        if (expected.stream().anyMatch(l -> l.startsWith("version-change "))) {
            result.mediation().forEach(m -> actual.add("version-change " + m.risk() + " " + m.library().artifactId()));
        }
        assertThat(actual).as("findings for fixture '%s'", name).containsExactlyInAnyOrderElementsOf(expected);
    }

    private static String describe(Finding f) {
        String origin = f.origin().isProject() ? "project" : f.origin().library().artifactId();
        return (f.isBreak() ? "break" : "warning") + " " + f.kind() + " " + origin + " | " + f.sentence();
    }

    /** All {@code .java} files below a directory, keyed by fully qualified class name. */
    private static Map<String, String> sources(Path dir) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                String rel = dir.relativize(f).toString().replace('\\', '/');
                out.put(rel.substring(0, rel.length() - ".java".length()).replace('/', '.'), Files.readString(f));
            }
        }
        return out;
    }
}
