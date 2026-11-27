package dev.tremor.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tremor.project.Gav;
import dev.tremor.resolve.MavenResolver;
import dev.tremor.resolve.ResolverConfig;
import dev.tremor.testing.Fixture;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpgradeCheckerTest {

    @TempDir
    Path temp;

    private Fixture fixture;
    private Path project;

    @BeforeEach
    void setUp() {
        fixture = new Fixture(temp);
        Gav strings1 = new Gav("org.text", "strings", "1.0");
        Path v1 = fixture.library(strings1, Map.of("text.Strings", """
                package text;
                public class Strings {
                    public static boolean isNullOrEmpty(String s) { return s == null || s.isEmpty(); }
                    public static String pad(String s) { return s; }
                }
                """));
        fixture.library(new Gav("org.text", "strings", "2.0"), Map.of("text.Strings", """
                package text;
                public class Strings {
                    public static boolean isEmpty(String s) { return s == null || s.isEmpty(); }
                    public static String pad(String s) { return s; }
                }
                """));
        // library-y was built against strings 1.0 and calls the method that 2.0 removes.
        fixture.library(new Gav("org.y", "library-y", "1.4.2"), List.of(v1), List.of("org.text:strings:1.0"),
                Map.of("y.Validator", """
                        package y;
                        public class Validator {
                            public static boolean valid(String s) {
                                return !text.Strings.isNullOrEmpty(s);
                            }
                        }
                        """));
        // library-z also depends on strings but only uses what still exists.
        fixture.library(new Gav("org.z", "library-z", "3.0"), List.of(v1), List.of("org.text:strings:1.0"),
                Map.of("z.Padder", "package z; public class Padder { public String p(String s) {"
                        + " return text.Strings.pad(s); } }"));
        project = fixture.project("app",
                List.of("org.text:strings:1.0", "org.y:library-y:1.4.2", "org.z:library-z:3.0"),
                List.of(v1), Map.of("app.Main", """
                        package app;
                        public class Main {
                            String a(String s) { return text.Strings.pad(s); }
                        }
                        """));
    }

    private CheckResult check(String version) {
        try (MavenResolver resolver = new MavenResolver(
                new ResolverConfig(fixture.cacheDir(), List.of(fixture.repo().url())))) {
            return new UpgradeChecker(resolver).check(project, "org.text", "strings", version);
        }
    }

    @Test
    void findsBreakInsideAnotherLibrary() {
        CheckResult result = check("2.0");

        assertThat(result.from()).isEqualTo(new Gav("org.text", "strings", "1.0"));
        assertThat(result.scannedLibraries()).extracting(Gav::artifactId)
                .containsExactlyInAnyOrder("library-y", "library-z");
        assertThat(result.breaks()).singleElement().satisfies(b -> {
            assertThat(b.origin().label()).isEqualTo("library-y 1.4.2");
            assertThat(b.action()).isEqualTo("calls Strings.isNullOrEmpty(String)");
            assertThat(b.reason()).isEqualTo("was removed");
            assertThat(b.consequence()).isEqualTo("NoSuchMethodError at runtime");
            assertThat(b.location().shortForm()).isEqualTo("Validator.java:4");
        });
        assertThat(result.hasBreaks()).isTrue();
    }

    @Test
    void flagsLibrariesThatGetAVersionTheyDidNotAskFor() {
        CheckResult result = check("2.0");

        assertThat(result.mediation()).extracting(m -> m.library().artifactId() + ":" + m.risk())
                .containsExactlyInAnyOrder("library-y:BREAKS", "library-z:LOW");
        MediationIssue y = result.mediation().stream()
                .filter(m -> m.library().artifactId().equals("library-y")).findFirst().orElseThrow();
        assertThat(y.requested()).isEqualTo("1.0");
        assertThat(y.resolved()).isEqualTo("2.0");
        assertThat(y.explanation()).startsWith("library-y 1.4.2 asks for strings 1.0 but will get 2.0");
    }

    @Test
    void downgradeIsRisky() {
        fixture.library(new Gav("org.text", "strings", "0.9"), Map.of("text.Strings", """
                package text;
                public class Strings {
                    public static boolean isNullOrEmpty(String s) { return s == null; }
                    public static String pad(String s) { return s; }
                }
                """));

        CheckResult result = check("0.9");

        assertThat(result.mediation()).isNotEmpty().allSatisfy(m -> {
            assertThat(m.risk()).isEqualTo(MediationIssue.Risk.RISKY);
            assertThat(m.explanation()).contains("OLDER");
        });
    }

    @Test
    void sameVersionIsANoOp() {
        CheckResult result = check("1.0");

        assertThat(result.findings()).isEmpty();
        assertThat(result.notes()).anyMatch(n -> n.contains("already uses"));
    }

    @Test
    void alwaysExplainsTheLimitsOfStaticAnalysis() {
        assertThat(check("2.0").notes()).contains(UpgradeChecker.REFLECTION_NOTE);
    }
}
