package dev.tremor.project;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

/**
 * Reads a Maven project's {@code pom.xml} into a {@link ProjectModel}.
 *
 * <p>This is a lightweight reader, not a full Maven model builder. It follows the parent
 * chain (on disk via {@code relativePath}, or through a {@link ParentPomLocator}), merges
 * {@code <properties>} and {@code <dependencyManagement>}, and fills in missing dependency
 * versions. Profiles are ignored. Imported BOMs are reported but not expanded here.
 */
public final class PomReader {

    private static final int MAX_PARENT_DEPTH = 20;

    private final ParentPomLocator parentLocator;

    public PomReader() {
        this(ParentPomLocator.NONE);
    }

    public PomReader(ParentPomLocator parentLocator) {
        this.parentLocator = parentLocator;
    }

    /** Reads {@code pom.xml} from a project directory, or a POM file given directly. */
    public ProjectModel read(Path projectDirOrPom) {
        Path pom = Files.isDirectory(projectDirOrPom) ? projectDirOrPom.resolve("pom.xml") : projectDirOrPom;
        if (!Files.isRegularFile(pom)) {
            throw new ProjectReadException("No pom.xml found at " + pom.toAbsolutePath().normalize()
                    + ". Point --project at a Maven project directory.");
        }
        List<String> warnings = new ArrayList<>();
        Model project = parse(pom);
        List<Model> lineage = lineage(project, pom, warnings); // project first, root ancestor last

        Gav gav = new Gav(
                firstNonNull(lineage, Model::getGroupId, m -> m.getParent() == null ? null : m.getParent().getGroupId()),
                project.getArtifactId(),
                firstNonNull(lineage, Model::getVersion, m -> m.getParent() == null ? null : m.getParent().getVersion()));
        Parent p = project.getParent();
        Gav parentGav = p == null ? null : new Gav(p.getGroupId(), p.getArtifactId(), p.getVersion());

        PropertyInterpolator interpolator = new PropertyInterpolator(properties(lineage, gav, parentGav));
        List<DeclaredDependency> managed = managedDependencies(lineage, interpolator);
        List<DeclaredDependency> direct = directDependencies(lineage, interpolator, managed, warnings);
        return new ProjectModel(pom.toAbsolutePath().normalize(), gav, parentGav,
                List.copyOf(direct), List.copyOf(managed), List.copyOf(warnings));
    }

    private List<Model> lineage(Model project, Path pom, List<String> warnings) {
        List<Model> lineage = new ArrayList<>();
        lineage.add(project);
        Model current = project;
        Path currentPom = pom;
        while (current.getParent() != null && lineage.size() <= MAX_PARENT_DEPTH) {
            Parent parent = current.getParent();
            Gav parentGav = new Gav(parent.getGroupId(), parent.getArtifactId(), parent.getVersion());
            Optional<Path> parentPom = localParent(currentPom, parent)
                    .or(() -> parentLocator.locate(parentGav));
            if (parentPom.isEmpty()) {
                warnings.add("Parent POM " + parentGav + " not found; properties and managed versions"
                        + " from it are unknown.");
                break;
            }
            currentPom = parentPom.get();
            current = parse(currentPom);
            lineage.add(current);
        }
        return lineage;
    }

    private Optional<Path> localParent(Path childPom, Parent parent) {
        String relative = parent.getRelativePath() == null ? "../pom.xml" : parent.getRelativePath();
        if (relative.isBlank()) {
            return Optional.empty();
        }
        Path candidate = childPom.toAbsolutePath().getParent().resolve(relative).normalize();
        if (Files.isDirectory(candidate)) {
            candidate = candidate.resolve("pom.xml");
        }
        if (!Files.isRegularFile(candidate)) {
            return Optional.empty();
        }
        Model model = parse(candidate);
        String groupId = model.getGroupId() != null ? model.getGroupId()
                : model.getParent() != null ? model.getParent().getGroupId() : null;
        String version = model.getVersion() != null ? model.getVersion()
                : model.getParent() != null ? model.getParent().getVersion() : null;
        boolean same = Objects.equals(groupId, parent.getGroupId())
                && Objects.equals(model.getArtifactId(), parent.getArtifactId())
                && Objects.equals(version, parent.getVersion());
        return same ? Optional.of(candidate) : Optional.empty();
    }

    private static Map<String, String> properties(List<Model> lineage, Gav gav, Gav parent) {
        Map<String, String> props = new LinkedHashMap<>();
        // Ancestors first so that children override them.
        for (int i = lineage.size() - 1; i >= 0; i--) {
            lineage.get(i).getProperties().forEach((k, v) -> props.put(k.toString(), v.toString()));
        }
        for (String prefix : List.of("project.", "pom.", "")) {
            putIfNotNull(props, prefix + "groupId", gav.groupId());
            putIfNotNull(props, prefix + "artifactId", gav.artifactId());
            putIfNotNull(props, prefix + "version", gav.version());
        }
        if (parent != null) {
            for (String prefix : List.of("project.parent.", "parent.")) {
                putIfNotNull(props, prefix + "groupId", parent.groupId());
                putIfNotNull(props, prefix + "artifactId", parent.artifactId());
                putIfNotNull(props, prefix + "version", parent.version());
            }
        }
        return props;
    }

    private static List<DeclaredDependency> managedDependencies(List<Model> lineage, PropertyInterpolator interp) {
        Map<String, DeclaredDependency> managed = new LinkedHashMap<>();
        for (Model model : lineage) { // nearest declaration wins
            if (model.getDependencyManagement() == null) {
                continue;
            }
            for (Dependency d : model.getDependencyManagement().getDependencies()) {
                DeclaredDependency dep = toDeclared(d, interp, null);
                managed.putIfAbsent(managementKey(dep), dep);
            }
        }
        return new ArrayList<>(managed.values());
    }

    private static List<DeclaredDependency> directDependencies(
            List<Model> lineage, PropertyInterpolator interp, List<DeclaredDependency> managed, List<String> warnings) {
        Map<String, DeclaredDependency> direct = new LinkedHashMap<>();
        for (Model model : lineage) { // dependencies are inherited from parents too
            for (Dependency d : model.getDependencies()) {
                DeclaredDependency dep = toDeclared(d, interp, findManaged(managed, d, interp));
                if (dep.version() == null) {
                    warnings.add("Could not work out the version of " + dep.key()
                            + " (it may come from an imported BOM or an unreadable parent).");
                }
                direct.putIfAbsent(managementKey(dep), dep);
            }
        }
        return new ArrayList<>(direct.values());
    }

    private static DeclaredDependency findManaged(
            List<DeclaredDependency> managed, Dependency d, PropertyInterpolator interp) {
        String groupId = interp.interpolate(d.getGroupId());
        String artifactId = interp.interpolate(d.getArtifactId());
        String type = d.getType() == null ? "jar" : d.getType();
        String classifier = interp.interpolate(d.getClassifier());
        return managed.stream()
                .filter(m -> m.matches(groupId, artifactId)
                        && m.type().equals(type)
                        && Objects.equals(m.classifier(), classifier))
                .findFirst()
                .orElse(null);
    }

    private static DeclaredDependency toDeclared(Dependency d, PropertyInterpolator interp, DeclaredDependency managed) {
        String version = interp.interpolate(d.getVersion());
        if (version == null && managed != null) {
            version = managed.version();
        }
        if (!PropertyInterpolator.isFullyResolved(version)) {
            version = null;
        }
        String scope = d.getScope() != null ? d.getScope()
                : managed != null && managed.scope() != null ? managed.scope() : "compile";
        return new DeclaredDependency(
                interp.interpolate(d.getGroupId()),
                interp.interpolate(d.getArtifactId()),
                version,
                scope,
                d.getType() == null ? "jar" : d.getType(),
                interp.interpolate(d.getClassifier()),
                d.isOptional());
    }

    private static String managementKey(DeclaredDependency d) {
        return d.key() + ":" + d.type() + ":" + (d.classifier() == null ? "" : d.classifier());
    }

    private static Model parse(Path pom) {
        try (InputStream in = Files.newInputStream(pom)) {
            return new MavenXpp3Reader().read(in, false);
        } catch (IOException | XmlPullParserException e) {
            throw new ProjectReadException("Could not read " + pom + ": " + e.getMessage(), e);
        }
    }

    @SafeVarargs
    private static String firstNonNull(List<Model> lineage, java.util.function.Function<Model, String>... getters) {
        for (Model model : lineage) {
            for (var getter : getters) {
                String value = getter.apply(model);
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    private static void putIfNotNull(Map<String, String> map, String key, String value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
