package dev.tremor.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tremor.project.Gav;
import dev.tremor.testing.TestRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CheckCommandTest {

    @TempDir
    Path temp;

    private Path project;
    private TestRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        repo = new TestRepository(temp.resolve("remote"));
        repo.library(new Gav("org.target", "target", "1.0"))
                .library(new Gav("org.target", "target", "2.0"))
                .library(new Gav("org.lib", "lib", "1.0"), "org.target:target:1.0");
        project = Files.createDirectories(temp.resolve("app"));
        Files.writeString(project.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId><artifactId>app</artifactId><version>1.0.0</version>
                  <dependencies>
                    <dependency><groupId>org.lib</groupId><artifactId>lib</artifactId><version>1.0</version></dependency>
                  </dependencies>
                </project>
                """);
    }

    private CliRunner check(String... extra) {
        List<String> args = new ArrayList<>(List.of("check",
                "--project", project.toString(),
                "--repository", repo.url(), "--no-central",
                "--cache-dir", temp.resolve("cache").toString()));
        args.addAll(List.of(extra));
        return CliRunner.run(args.toArray(String[]::new));
    }

    @Test
    void printsParsedOptions() {
        CliRunner result = check("--dependency", "org.target:target", "--to", "2.0",
                "--format", "JSON", "--output", "report.json");

        assertThat(result.exitCode()).isEqualTo(ExitCodes.SAFE);
        assertThat(result.out())
                .contains("com.example:app:1.0.0")
                .contains("dependency: org.target:target (not a direct dependency)")
                .contains("current:    1.0")
                .contains("to:         2.0")
                .contains("tree:       2 artifacts")
                .contains("format:     json")
                .contains("output:     report.json");
    }

    @Test
    void defaultsToTextFormat() {
        CliRunner result = check("--dependency", "org.lib:lib", "--to", "1.0");

        assertThat(result.exitCode()).isEqualTo(ExitCodes.SAFE);
        assertThat(result.out()).contains("format:     text").contains("output:     (terminal)");
    }

    @Test
    void flagsDependencyThatIsNotInTheTree() {
        CliRunner result = check("--dependency", "org.example:nowhere", "--to", "1.0");

        assertThat(result.out()).contains("(not a direct dependency)")
                .contains("current:    not in the dependency tree");
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
        CliRunner result = CliRunner.run("check", "--project", empty.toString(), "--no-central",
                "--repository", repo.url(), "--dependency", "a:b", "--to", "1.0");

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
