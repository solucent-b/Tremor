package dev.tremor.cli;

import dev.tremor.analysis.CheckResult;
import dev.tremor.analysis.Finding;
import dev.tremor.analysis.UpgradeChecker;
import dev.tremor.resolve.MavenResolver;
import dev.tremor.resolve.ResolverConfig;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
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

    @Option(names = "--dependency", paramLabel = "GROUP:ARTIFACT", required = true,
            converter = DependencyCoordinateConverter.class,
            description = "Dependency to upgrade, e.g. com.google.guava:guava.")
    DependencyCoordinate dependency;

    @Option(names = "--to", paramLabel = "VERSION", required = true,
            description = "Version to upgrade to, e.g. 33.0.0-jre.")
    String toVersion;

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

    ResolverConfig resolverConfig() {
        Path cache = cacheDir != null ? cacheDir : ResolverConfig.defaultCacheDir();
        List<String> urls = new ArrayList<>(repositories);
        if (!noCentral) {
            urls.add(ResolverConfig.MAVEN_CENTRAL);
        }
        if (urls.isEmpty()) {
            throw new picocli.CommandLine.ParameterException(spec.commandLine(),
                    "--no-central needs at least one --repository URL");
        }
        return new ResolverConfig(cache, urls);
    }

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        try (MavenResolver resolver = new MavenResolver(resolverConfig())) {
            CheckResult result = new UpgradeChecker(resolver)
                    .check(project, dependency.groupId(), dependency.artifactId(), toVersion);
            out.println("Checking " + result.from() + " -> " + result.to().version() + " for " + result.project());
            for (Finding f : result.findings()) {
                out.println((f.isBreak() ? "BREAK  " : "WARN   ") + f.sentence()
                        + (f.consequence() == null ? "" : " -> " + f.consequence()));
            }
            out.println(result.breaks().size() + " breaks, " + result.warnings().size() + " warnings");
            out.flush();
            return result.hasBreaks() ? ExitCodes.BREAKS_FOUND : ExitCodes.SAFE;
        }
    }
}
