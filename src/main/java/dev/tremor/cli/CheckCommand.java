package dev.tremor.cli;

import dev.tremor.analysis.CheckResult;
import dev.tremor.analysis.AllUpdatesChecker;
import dev.tremor.analysis.UpdateRow;
import dev.tremor.analysis.UpgradeChecker;
import dev.tremor.resolve.MavenResolver;
import dev.tremor.resolve.ResolverConfig;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

/** {@code tremor check}: analyse one dependency upgrade against a project. */
@Command(
        name = "check",
        mixinStandardHelpOptions = true,
        versionProvider = TremorVersion.class,
        description = "Check what upgrading a dependency would break in a project.")
public final class CheckCommand implements Callable<Integer> {

    @Spec
    private CommandSpec spec;

    @Option(names = "--project", paramLabel = "DIR", defaultValue = ".",
            description = "Project directory containing pom.xml (default: ${DEFAULT-VALUE}).")
    Path project;

    @Option(names = "--dependency", paramLabel = "GROUP:ARTIFACT",
            converter = DependencyCoordinateConverter.class,
            description = "Dependency to upgrade, e.g. com.google.guava:guava.")
    DependencyCoordinate dependency;

    @Option(names = "--to", paramLabel = "VERSION",
            description = "Version to upgrade to, e.g. 33.0.0-jre.")
    String toVersion;

    @Option(names = "--all-updates",
            description = "Check the newest stable version of every direct dependency instead of one upgrade.")
    boolean allUpdates;

    @Option(names = "--format", paramLabel = "FORMAT", defaultValue = "text",
            description = "Report format: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE}).")
    ReportFormat format;

    @Option(names = "--output", paramLabel = "FILE",
            description = "Write the report to this file instead of the terminal.")
    Path output;

    @Option(names = "--repository", paramLabel = "URL",
            description = "Extra Maven repository to search before Maven Central (repeatable).")
    List<String> repositories = new ArrayList<>();

    @Option(names = "--cache-dir", paramLabel = "DIR",
            description = "Where to cache downloads (default: ~/.tremor/cache or $TREMOR_CACHE).")
    Path cacheDir;

    @Option(names = "--no-central",
            description = "Do not use Maven Central; only the --repository URLs (e.g. a company mirror).")
    boolean noCentral;

    @Option(names = "--no-color", description = "Do not use colors in the terminal report.")
    boolean noColor;

    ResolverConfig resolverConfig() {
        Path cache = cacheDir != null ? cacheDir : ResolverConfig.defaultCacheDir();
        List<String> urls = new ArrayList<>(repositories);
        if (!noCentral) {
            urls.add(ResolverConfig.MAVEN_CENTRAL);
        }
        if (urls.isEmpty()) {
            throw new ParameterException(spec.commandLine(),
                    "--no-central needs at least one --repository URL");
        }
        return new ResolverConfig(cache, urls);
    }

    @Override
    public Integer call() throws IOException {
        validate();
        try (MavenResolver resolver = new MavenResolver(resolverConfig())) {
            UpgradeChecker checker = new UpgradeChecker(resolver);
            if (allUpdates) {
                List<UpdateRow> rows = new AllUpdatesChecker(resolver, checker).check(project);
                ReportOutput.writeUpdates(spec, output, format, noColor, rows);
                return rows.stream().anyMatch(r -> r.verdict() == UpdateRow.Verdict.BREAKS)
                        ? ExitCodes.BREAKS_FOUND : ExitCodes.SAFE;
            }
            CheckResult result = checker.check(project, dependency.groupId(), dependency.artifactId(), toVersion);
            ReportOutput.write(spec, output, format, noColor, result);
            return result.hasBreaks() ? ExitCodes.BREAKS_FOUND : ExitCodes.SAFE;
        }
    }

    private void validate() {
        if (allUpdates && (dependency != null || toVersion != null)) {
            throw new ParameterException(spec.commandLine(),
                    "Use either --all-updates or --dependency with --to, not both");
        }
        if (!allUpdates && (dependency == null || toVersion == null)) {
            throw new ParameterException(spec.commandLine(),
                    "Missing required options: --dependency=GROUP:ARTIFACT and --to=VERSION (or use --all-updates)");
        }
    }
}
