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
        assertThat(result.out())
                .contains("src/main/java/app/Main.java:5")
                .contains("calls Api.gone(), which was removed")
                .contains("❌ 1 break, 0 warnings across 1 file");
    }

    @Test
    void safeUpgradeExitsWithZero() {
        CliRunner result = check("--dependency", "org.target:target", "--to", "1.1");

        assertThat(result.exitCode()).isEqualTo(ExitCodes.SAFE);
        assertThat(result.out()).contains("✅ Safe: nothing you use changed.");
    }

    @Test
    void writesReportToAFile() throws Exception {
        Path report = temp.resolve("out/report.txt");

        CliRunner result = check("--dependency", "org.target:target", "--to", "2.0", "--output", report.toString());

        assertThat(result.exitCode()).isEqualTo(ExitCodes.BREAKS_FOUND);
        assertThat(Files.readString(report)).contains("calls Api.gone()");
        assertThat(result.err()).contains("report written to");
    }

    @Test
    void jsonAndHtmlFormats() throws Exception {
        CliRunner json = check("--dependency", "org.target:target", "--to", "2.0", "--format", "json");
        assertThat(json.exitCode()).isEqualTo(ExitCodes.BREAKS_FOUND);
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(json.out()).get("verdict").asText())
                .isEqualTo("breaks");

        Path html = temp.resolve("report.html");
        check("--dependency", "org.target:target", "--to", "2.0", "--format", "html", "--output", html.toString());
        assertThat(Files.readString(html)).contains("<!doctype html>").contains("calls Api.gone()");
    }

    @Test
    void allUpdatesShowsATable() throws Exception {
        fixture.library(new Gav("org.other", "fine", "1.0"), Map.of("o.Fine", "package o; public class Fine {}"));
        fixture.library(new Gav("org.other", "fine", "1.2"), Map.of("o.Fine", "package o; public class Fine {}"));
        fixture.library(new Gav("org.other", "fine", "2.0-beta1"), Map.of("o.Fine", "package o; class Fine {}"));
        Files.writeString(project.resolve("pom.xml"), Files.readString(project.resolve("pom.xml")).replace(
                "</dependencies>", "<dependency><groupId>org.other</groupId><artifactId>fine</artifactId>"
                        + "<version>1.0</version></dependency></dependencies>"));

        CliRunner result = check("--all-updates");

        assertThat(result.exitCode()).isEqualTo(ExitCodes.BREAKS_FOUND);
        assertThat(result.out())
                .containsPattern("org.target:target\\s+1.0\\s+2.0\\s+❌ breaks \\(1 break\\)")
                .containsPattern("org.other:fine\\s+1.0\\s+1.2\\s+✅ safe")
                .contains("1 safe, 0 risky, 1 with breaks");

        CliRunner json = check("--all-updates", "--format", "json");
        var tree = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json.out());
        assertThat(tree.get("mode").asText()).isEqualTo("all-updates");
        assertThat(tree.get("updates").get(0).get("verdict").asText()).isEqualTo("breaks");
        assertThat(tree.get("updates").get(0).get("report").get("breaks")).hasSize(1);
    }

    @Test
    void allUpdatesCannotBeCombinedWithDependency() {
        CliRunner result = check("--all-updates", "--dependency", "a:b");

        assertThat(result.exitCode()).isEqualTo(ExitCodes.TOOL_ERROR);
        assertThat(result.err()).contains("not both");
    }

    @Test
    void gradleProjectsAreChecked() throws Exception {
        Path gradle = Files.createDirectories(temp.resolve("gradle-app"));
        Files.writeString(gradle.resolve("build.gradle"), """
                plugins { id 'java' }
                dependencies { implementation 'org.target:target:1.0' }
                """);
        Path classes = project.resolve("target/classes");
        Path gradleClasses = gradle.resolve("build/classes/java/main");
        try (var files = Files.walk(classes)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                Path to = gradleClasses.resolve(classes.relativize(f).toString());
                Files.createDirectories(to.getParent());
                Files.copy(f, to);
            }
        }
        List<String> args = new ArrayList<>(List.of("check", "--project", gradle.toString(),
                "--dependency", "org.target:target", "--to", "2.0"));
        args.addAll(fixture.offlineArgs());

        CliRunner result = CliRunner.run(args.toArray(String[]::new));

        assertThat(result.exitCode()).isEqualTo(ExitCodes.BREAKS_FOUND);
        assertThat(result.out()).contains("calls Api.gone(), which was removed");
    }

    @Test
    void uncompiledGradleProjectAsksToRunGradle() throws Exception {
        Path gradle = Files.createDirectories(temp.resolve("gradle-empty"));
        Files.writeString(gradle.resolve("build.gradle.kts"), "dependencies { implementation(\"org.target:target:1.0\") }");
        List<String> args = new ArrayList<>(List.of("check", "--project", gradle.toString(),
                "--dependency", "org.target:target", "--to", "2.0"));
        args.addAll(fixture.offlineArgs());

        CliRunner result = CliRunner.run(args.toArray(String[]::new));

        assertThat(result.exitCode()).isEqualTo(ExitCodes.TOOL_ERROR);
        assertThat(result.err()).contains("./gradlew classes");
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
