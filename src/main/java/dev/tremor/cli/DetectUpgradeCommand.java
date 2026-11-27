package dev.tremor.cli;

import java.io.PrintWriter;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * {@code tremor detect-upgrade --title "..."}: reads a Dependabot or Renovate PR title and prints
 * {@code key=value} lines that a CI script can append to {@code $GITHUB_OUTPUT}.
 */
@Command(
        name = "detect-upgrade",
        mixinStandardHelpOptions = true,
        versionProvider = TremorVersion.class,
        description = "Work out which dependency a Dependabot or Renovate pull request title upgrades.")
public final class DetectUpgradeCommand implements Callable<Integer> {

    @Spec
    private CommandSpec spec;

    @Option(names = "--title", required = true, paramLabel = "TITLE", description = "The pull request title.")
    String title;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        Optional<UpgradeTitle> upgrade = UpgradeTitle.parse(title);
        out.println("detected=" + upgrade.isPresent());
        upgrade.ifPresent(u -> {
            out.println("dependency=" + u.dependency());
            out.println("from=" + (u.from() == null ? "" : u.from()));
            out.println("to=" + u.to());
            out.println("directory=" + (u.directory() == null ? "" : u.directory()));
        });
        out.flush();
        return ExitCodes.SAFE;
    }
}
