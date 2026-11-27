package dev.tremor.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tremor.project.Gav;
import dev.tremor.testing.Fixture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CheckCommandTest {

    @TempDir
    Path temp;

    private Fixture fixture;
    private Path project;

    @BeforeEach
    void setUp() {
        fixture = new Fixture(temp);
        Path v1 = fixture.library(new Gav("org.target", "target", "1.0"), Map.of("t.Api", """
                package t;
                public class Api {
                    public static String keep() { return ""; }
                    public static String gone() { return ""; }
                }
                """));
        fixture.library(new Gav("org.target", "target", "2.0"), Map.of("t.Api", """
                package t;
                public class Api { public static String keep() { return ""; } }
                """));
        fixture.library(new Gav("org.target", "target", "1.1"), Map.of("t.Api", """
                package t;
                public class Api {
                    public static String keep() { return ""; }
                    public static String gone() { return ""; }
                    public static String extra() { return ""; }
                }
                """));
        project = fixture.project("app", List.of("org.target:target:1.0"), List.of(v1), Map.of("app.Main", """
                package app;
                public class Main {
                    public static void main(String[] args) {
                        System.out.println(t.Api.keep());
                        System.out.println(t.Api.gone());
                    }
                }
                """));
    }

    private CliRunner check(String... extra) {
        List<String> args = new ArrayList<>(List.of("check", "--project", project.toString()));
        args.addAll(fixture.offlineArgs());
        args.addAll(List.of(extra));
        return CliRunner.run(args.toArray(String[]::new));
    }

    @Test
    void reportsBreaksAndExitsWithOne() {
        CliRunner result = check("--dependency", "org.target:target", "--to", "2.0");

        assertThat(result.exitCode()).isEqualTo(ExitCodes.BREAKS_FOUND);
        assertThat(result.out()).contains("Main.java:5 calls Api.gone(), which was removed");
    }

    @Test
    void safeUpgradeExitsWithZero() {
        CliRunner result = check("--dependency", "org.target:target", "--to", "1.1");

        assertThat(result.exitCode()).isEqualTo(ExitCodes.SAFE);
        assertThat(result.out()).contains("0 breaks");
    }

    @Test
    void dependencyNotInTreeIsToolError() {
        CliRunner result = check("--dependency", "org.example:nowhere", "--to", "1.0");

        assertThat(result.exitCode()).isEqualTo(ExitCodes.TOOL_ERROR);
        assertThat(result.err()).contains("is not in the dependency tree");
    }

    @Test
    void missingTargetVersionIsToolError() {
        CliRunner result = check("--dependency", "org.target:target", "--to", "9.9");

        assertThat(result.exitCode()).isEqualTo(ExitCodes.TOOL_ERROR);
        assertThat(result.err()).contains("version 9.9 exists");
    }

    @Test
    void uncompiledProjectAsksToRunMavenCompile() throws Exception {
        Files.walk(project.resolve("target")).sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> p.toFile().delete());

        CliRunner result = check("--dependency", "org.target:target", "--to", "2.0");

        assertThat(result.exitCode()).isEqualTo(ExitCodes.TOOL_ERROR);
        assertThat(result.err()).contains("mvn compile");
    }

    @Test
    void noCentralWithoutRepositoryIsToolError() {
        CliRunner result = CliRunner.run("check", "--project", project.toString(), "--no-central",
                "--dependency", "a:b", "--to", "1.0");

        assertThat(result.exitCode()).isEqualTo(ExitCodes.TOOL_ERROR);
        assertThat(result.err()).contains("--no-central");
    }

    @Test
    void missingRequiredOptionIsToolError() {
        CliRunner result = CliRunner.run("check", "--dependency", "a:b");

        assertThat(result.exitCode()).isEqualTo(ExitCodes.TOOL_ERROR);
        assertThat(result.err()).contains("--to");
    }

    @Test
    void malformedDependencyIsToolError() {
        CliRunner result = CliRunner.run("check", "--dependency", "guava", "--to", "1.0");

        assertThat(result.exitCode()).isEqualTo(ExitCodes.TOOL_ERROR);
        assertThat(result.err()).contains("groupId:artifactId");
    }

    @Test
    void missingPomIsToolError(@TempDir Path empty) {
        List<String> args = new ArrayList<>(List.of("check", "--project", empty.toString(),
                "--dependency", "a:b", "--to", "1.0"));
        args.addAll(fixture.offlineArgs());

        CliRunner result = CliRunner.run(args.toArray(String[]::new));

        assertThat(result.exitCode()).isEqualTo(ExitCodes.TOOL_ERROR);
        assertThat(result.err()).contains("No pom.xml found");
    }

    @Test
    void checkHasItsOwnHelp() {
        CliRunner result = CliRunner.run("check", "--help");

        assertThat(result.exitCode()).isZero();
        assertThat(result.out()).contains("--project", "--dependency", "--to", "--format", "--output");
    }
}
