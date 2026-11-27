package dev.tremor.resolve;

import dev.tremor.project.DeclaredDependency;
import dev.tremor.project.Gav;
import dev.tremor.project.GradleBuildReader;
import dev.tremor.project.PomReader;
import dev.tremor.project.ProjectModel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.aether.graph.Dependency;

/**
 * Reads a project's POM, fetching remote parent POMs and expanding imported BOMs through the
 * resolver so that every dependency version is known where possible.
 */
public final class ProjectLoader {

    private final MavenResolver resolver;

    public ProjectLoader(MavenResolver resolver) {
        this.resolver = resolver;
    }

    /** Reads {@code pom.xml}, or a Gradle build when there is no POM. */
    public ProjectModel load(Path projectDir) {
        boolean maven = !java.nio.file.Files.isDirectory(projectDir)
                || java.nio.file.Files.isRegularFile(projectDir.resolve("pom.xml"))
                || !GradleBuildReader.isGradleProject(projectDir);
        ProjectModel raw = maven ? new PomReader(resolver::resolvePom).read(projectDir)
                : new GradleBuildReader().read(projectDir);
        return expandBoms(raw);
    }

    /** Fills in versions that come from imported BOMs (Maven) or platforms (Gradle). */
    public ProjectModel expandBoms(ProjectModel raw) {
        if (raw.importedBoms().isEmpty()) {
            return raw;
        }
        List<String> warnings = new ArrayList<>(raw.warnings());
        List<DeclaredDependency> managed = new ArrayList<>(raw.managedDependencies());
        for (DeclaredDependency bom : raw.importedBoms()) {
            if (bom.version() == null) {
                warnings.add("Imported BOM " + bom.key() + " has no known version; skipped.");
                continue;
            }
            try {
                for (Dependency d : resolver.managedDependenciesOf(new Gav(bom.groupId(), bom.artifactId(), bom.version()))) {
                    managed.add(fromAether(d));
                }
            } catch (ResolutionException e) {
                warnings.add("Could not read imported BOM " + bom.key() + ":" + bom.version() + ": " + e.getMessage());
            }
        }
        List<DeclaredDependency> direct = new ArrayList<>();
        for (DeclaredDependency d : raw.dependencies()) {
            if (d.version() != null) {
                direct.add(d);
                continue;
            }
            DeclaredDependency filled = managed.stream()
                    .filter(m -> m.matches(d.groupId(), d.artifactId()) && m.version() != null)
                    .findFirst()
                    .map(m -> new DeclaredDependency(d.groupId(), d.artifactId(), m.version(), d.scope(),
                            d.type(), d.classifier(), d.optional()))
                    .orElse(d);
            direct.add(filled);
        }
        // Drop warnings about versions that the BOMs have now supplied.
        warnings.removeIf(w -> w.startsWith("Could not work out the version of ") && direct.stream()
                .anyMatch(d -> d.version() != null && w.contains(" " + d.key() + " ")));
        return new ProjectModel(raw.buildFile(), raw.gav(), raw.parent(), List.copyOf(direct),
                List.copyOf(managed), List.copyOf(warnings));
    }

    private static DeclaredDependency fromAether(Dependency d) {
        var a = d.getArtifact();
        String scope = d.getScope() == null || d.getScope().isEmpty() ? null : d.getScope();
        String classifier = a.getClassifier().isEmpty() ? null : a.getClassifier();
        String type = a.getProperty("type", a.getExtension());
        return new DeclaredDependency(a.getGroupId(), a.getArtifactId(), a.getVersion(), scope, type,
                classifier, Boolean.TRUE.equals(d.getOptional()));
    }
}
