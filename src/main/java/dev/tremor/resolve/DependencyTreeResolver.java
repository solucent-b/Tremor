package dev.tremor.resolve;

import dev.tremor.project.DeclaredDependency;
import dev.tremor.project.Gav;
import dev.tremor.project.ProjectModel;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.ArtifactType;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.util.graph.manager.DependencyManagerUtils;
import org.eclipse.aether.util.graph.transformer.ConflictResolver;

/** Builds the transitive {@link DependencyTree} of a project, optionally with one dependency upgraded. */
public final class DependencyTreeResolver {

    private final MavenResolver resolver;

    public DependencyTreeResolver(MavenResolver resolver) {
        this.resolver = resolver;
    }

    /** The tree as the project is today. */
    public DependencyTree resolve(ProjectModel project) {
        return resolve(project, null, null);
    }

    /**
     * The tree after forcing {@code groupId:artifactId} to {@code newVersion}, the same way a user
     * would by editing the version in their POM (direct dependency and dependency management).
     */
    public DependencyTree resolveUpgraded(ProjectModel project, String groupId, String artifactId, String newVersion) {
        return resolve(project, groupId + ":" + artifactId, newVersion);
    }

    private DependencyTree resolve(ProjectModel project, String upgradeKey, String newVersion) {
        List<Dependency> direct = new ArrayList<>();
        for (DeclaredDependency d : project.dependencies()) {
            String version = d.key().equals(upgradeKey) ? newVersion : d.version();
            if (version != null) {
                direct.add(toAether(d, version));
            }
        }
        List<Dependency> managed = new ArrayList<>();
        if (upgradeKey != null) {
            String[] ga = upgradeKey.split(":");
            managed.add(new Dependency(new DefaultArtifact(ga[0], ga[1], "jar", newVersion), null));
        }
        for (DeclaredDependency d : project.managedDependencies()) {
            if (d.version() != null && !"import".equals(d.scope())) {
                managed.add(toAether(d, d.version()));
            }
        }
        DependencyNode root = resolver.collect(direct, managed);
        return new DependencyTree(root.getChildren().stream().map(DependencyTreeResolver::toNode).toList());
    }

    private Dependency toAether(DeclaredDependency d, String version) {
        ArtifactType type = resolver.session().getArtifactTypeRegistry().get(d.type());
        String extension = type != null ? type.getExtension() : d.type();
        String classifier = d.classifier() != null ? d.classifier() : type != null ? type.getClassifier() : "";
        Artifact artifact = new DefaultArtifact(d.groupId(), d.artifactId(), classifier, extension, version, type);
        return new Dependency(artifact, d.scope(), d.optional());
    }

    static TreeNode toNode(DependencyNode node) {
        Artifact a = node.getArtifact();
        Gav gav = new Gav(a.getGroupId(), a.getArtifactId(), a.getVersion());
        String premanaged = DependencyManagerUtils.getPremanagedVersion(node);
        String requested = premanaged != null ? premanaged : a.getVersion();
        String selected = a.getVersion();
        Object winner = node.getData().get(ConflictResolver.NODE_DATA_WINNER);
        if (winner instanceof DependencyNode w && w.getArtifact() != null) {
            selected = w.getArtifact().getVersion();
        }
        String scope = node.getDependency() != null ? node.getDependency().getScope() : "";
        boolean optional = node.getDependency() != null && node.getDependency().isOptional();
        return new TreeNode(gav, requested, selected, scope, optional,
                node.getChildren().stream().map(DependencyTreeResolver::toNode).toList());
    }
}
