package dev.tremor.project;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * What Tremor knows about a project from its build file: its own coordinates, its direct
 * dependencies and its managed dependency versions. Version strings are already interpolated.
 *
 * @param warnings things Tremor could not fully work out (e.g. a parent POM it could not find)
 */
public record ProjectModel(
        Path pomFile,
        Gav gav,
        Gav parent,
        List<DeclaredDependency> dependencies,
        List<DeclaredDependency> managedDependencies,
        List<String> warnings) {

    public Optional<DeclaredDependency> findDependency(String groupId, String artifactId) {
        return dependencies.stream().filter(d -> d.matches(groupId, artifactId)).findFirst();
    }

    public Optional<DeclaredDependency> findManaged(String groupId, String artifactId) {
        return managedDependencies.stream().filter(d -> d.matches(groupId, artifactId)).findFirst();
    }

    /** BOMs imported in {@code <dependencyManagement>} with {@code <scope>import</scope>}. */
    public List<DeclaredDependency> importedBoms() {
        return managedDependencies.stream()
                .filter(d -> "import".equals(d.scope()) && "pom".equals(d.type()))
                .toList();
    }
}
