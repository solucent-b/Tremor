package dev.tremor.resolve;

import dev.tremor.project.Gav;
import java.util.List;

/**
 * One node of a resolved dependency tree.
 *
 * @param gav the coordinates as selected for this node (for a losing node, the version it asked for)
 * @param requestedVersion the version the declaring POM asked for, before dependency management
 * @param selectedVersion the version Maven actually puts on the classpath for this group:artifact
 * @param scope Maven scope
 * @param optional whether the dependency was declared optional
 * @param children transitive dependencies (empty for losing nodes)
 */
public record TreeNode(
        Gav gav,
        String requestedVersion,
        String selectedVersion,
        String scope,
        boolean optional,
        List<TreeNode> children) {

    public String key() {
        return gav.groupId() + ":" + gav.artifactId();
    }

    /** True when Maven resolved a different version than this node asked for. */
    public boolean isMediated() {
        return !requestedVersion.equals(selectedVersion);
    }

    /** True when this node lost version mediation and is not on the classpath itself. */
    public boolean isLoser() {
        return !gav.version().equals(selectedVersion);
    }

    public boolean matches(String groupId, String artifactId) {
        return gav.groupId().equals(groupId) && gav.artifactId().equals(artifactId);
    }
}
