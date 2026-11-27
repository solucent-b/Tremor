package dev.tremor.resolve;

import dev.tremor.project.Gav;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/** A project's resolved dependency tree. The root itself (the project) is not a node. */
public record DependencyTree(List<TreeNode> roots) {

    /** Visits every node, parents before children. */
    public void forEach(Consumer<TreeNode> action) {
        roots.forEach(n -> visit(n, action));
    }

    /** Every artifact Maven puts on the classpath, keyed by {@code groupId:artifactId}. */
    public Map<String, Gav> selected() {
        Map<String, Gav> out = new LinkedHashMap<>();
        forEach(n -> {
            if (!n.isLoser()) {
                out.putIfAbsent(n.key(), n.gav());
            }
        });
        return out;
    }

    public Optional<Gav> selected(String groupId, String artifactId) {
        return Optional.ofNullable(selected().get(groupId + ":" + artifactId));
    }

    /** Edges {@code parent -> child} where the child is the given artifact (any version). */
    public List<Edge> edgesTo(String groupId, String artifactId) {
        List<Edge> edges = new ArrayList<>();
        for (TreeNode root : roots) {
            if (root.matches(groupId, artifactId)) {
                edges.add(new Edge(null, root));
            }
            collectEdges(root, groupId, artifactId, edges);
        }
        return edges;
    }

    /** All edges in the tree, including direct dependencies of the project (parent = null). */
    public List<Edge> edges() {
        List<Edge> edges = new ArrayList<>();
        for (TreeNode root : roots) {
            edges.add(new Edge(null, root));
            allEdges(root, edges);
        }
        return edges;
    }

    /** The path from the project to the first node matching, for "how did this get here" messages. */
    public List<TreeNode> pathTo(String groupId, String artifactId) {
        for (TreeNode root : roots) {
            List<TreeNode> path = new ArrayList<>();
            if (findPath(root, groupId, artifactId, path)) {
                return path;
            }
        }
        return List.of();
    }

    /** A dependency relation: {@code parent} (null for the project) depends on {@code child}. */
    public record Edge(TreeNode parent, TreeNode child) {
    }

    private static void visit(TreeNode node, Consumer<TreeNode> action) {
        action.accept(node);
        node.children().forEach(c -> visit(c, action));
    }

    private static void collectEdges(TreeNode node, String g, String a, List<Edge> edges) {
        for (TreeNode child : node.children()) {
            if (child.matches(g, a)) {
                edges.add(new Edge(node, child));
            }
            collectEdges(child, g, a, edges);
        }
    }

    private static void allEdges(TreeNode node, List<Edge> edges) {
        for (TreeNode child : node.children()) {
            edges.add(new Edge(node, child));
            allEdges(child, edges);
        }
    }

    private static boolean findPath(TreeNode node, String g, String a, List<TreeNode> path) {
        path.add(node);
        if (node.matches(g, a)) {
            return true;
        }
        for (TreeNode child : node.children()) {
            if (findPath(child, g, a, path)) {
                return true;
            }
        }
        path.remove(path.size() - 1);
        return false;
    }
}
