package dev.tremor.cli;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TremorCommandTest {

    @Test
    void helpListsCheckSubcommand() {
        CliRunner result = CliRunner.run("--help");

        assertThat(result.exitCode()).isZero();
        assertThat(result.out()).contains("Usage: tremor").contains("check");
    }

    @Test
    void versionPrintsProjectVersion() {
        CliRunner result = CliRunner.run("--version");

        assertThat(result.exitCode()).isZero();
        assertThat(result.out()).startsWith("tremor ").doesNotContain("${");
    }

    @Test
    void noArgumentsShowsUsage() {
        CliRunner result = CliRunner.run();

        assertThat(result.exitCode()).isZero();
        assertThat(result.out()).contains("Usage: tremor");
    }

    @Test
    void unknownSubcommandIsToolError() {
        CliRunner result = CliRunner.run("nope");

        assertThat(result.exitCode()).isEqualTo(ExitCodes.TOOL_ERROR);
    }
}
