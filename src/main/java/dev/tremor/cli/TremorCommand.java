package dev.tremor.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/** Top-level {@code tremor} command. Holds the subcommands. */
@Command(
        name = "tremor",
        mixinStandardHelpOptions = true,
        versionProvider = TremorVersion.class,
        description = "Feel the shake before you upgrade: find what a dependency upgrade breaks.",
        subcommands = {CheckCommand.class, DetectUpgradeCommand.class, CommandLine.HelpCommand.class})
public final class TremorCommand implements Runnable {

    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec;

    /** Builds a configured command line. Exit codes follow {@link ExitCodes}. */
    public static CommandLine newCommandLine() {
        CommandLine cmd = new CommandLine(new TremorCommand());
        cmd.setCaseInsensitiveEnumValuesAllowed(true);
        cmd.setExecutionExceptionHandler((ex, commandLine, parseResult) -> {
            commandLine.getErr().println("tremor: error: " + ex.getMessage());
            return ExitCodes.TOOL_ERROR;
        });
        return cmd;
    }

    @Override
    public void run() {
        // Running "tremor" with no subcommand just shows help.
        spec.commandLine().usage(spec.commandLine().getOut());
    }
}
