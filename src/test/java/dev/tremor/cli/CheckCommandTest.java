package dev.tremor.cli;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CheckCommandTest {

    @Test
    void printsParsedOptions() {
        CliRunner result = CliRunner.run("check",
                "--project", "my-app",
                "--dependency", "com.google.guava:guava",
                "--to", "33.0.0-jre",
                "--format", "JSON",
                "--output", "report.json");

        assertThat(result.exitCode()).isEqualTo(ExitCodes.SAFE);
        assertThat(result.out())
                .contains("my-app")
                .contains("dependency: com.google.guava:guava")
                .contains("to:         33.0.0-jre")
                .contains("format:     json")
                .contains("output:     report.json");
    }

    @Test
    void defaultsToCurrentDirectoryAndTextFormat() {
        CliRunner result = CliRunner.run("check", "--dependency", "a:b", "--to", "1.0");

        assertThat(result.exitCode()).isEqualTo(ExitCodes.SAFE);
        assertThat(result.out()).contains("format:     text").contains("output:     (terminal)");
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
    void checkHasItsOwnHelp() {
        CliRunner result = CliRunner.run("check", "--help");

        assertThat(result.exitCode()).isZero();
        assertThat(result.out()).contains("--project", "--dependency", "--to", "--format", "--output");
    }
}
