package dev.tremor.cli;

import dev.tremor.project.DeclaredDependency;
import dev.tremor.project.PomReader;
import dev.tremor.project.ProjectModel;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

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

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        ProjectModel model = new PomReader().read(project);
        model.warnings().forEach(w -> spec.commandLine().getErr().println("tremor: warning: " + w));

        Optional<DeclaredDependency> declared =
                model.findDependency(dependency.groupId(), dependency.artifactId());
        String current = declared.map(DeclaredDependency::version)
                .or(() -> model.findManaged(dependency.groupId(), dependency.artifactId())
                        .map(DeclaredDependency::version))
                .orElse(null);

        out.println("project:    " + model.gav() + " (" + model.pomFile() + ")");
        out.println("dependency: " + dependency
                + (declared.isPresent() ? "" : " (not a direct dependency)"));
        out.println("current:    " + (current == null ? "unknown" : current));
        out.println("to:         " + toVersion);
        out.println("format:     " + format.name().toLowerCase());
        out.println("output:     " + (output == null ? "(terminal)" : output));
        out.flush();
        return ExitCodes.SAFE;
    }
}
