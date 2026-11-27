package dev.tremor.analysis;

import dev.tremor.project.Gav;
import dev.tremor.resolve.DependencyTree;
import dev.tremor.resolve.TreeNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Finds libraries that, after the upgrade, will get a different version of a dependency than the
 * one they declared. Maven picks one version per artifact ("nearest wins"), so a library built for
 * an older or newer version may end up running against something it was never tested with.
 *
 * <p>Only mismatches caused by the upgrade are reported, not ones that already existed.
 */
public final class MediationChecker {

    private MediationChecker() {
    }

    public static List<MediationIssue> check(DependencyTree before, DependencyTree after, String groupId,
            String artifactId, List<Finding> findings) {
        String target = groupId + ":" + artifactId;
        Map<String, Gav> was = before.selected();
        Map<String, Gav> now = after.selected();
        Set<String> changed = new HashSet<>();
        now.forEach((key, gav) -> {
            if (!Objects.equals(was.get(key), gav)) {
                changed.add(key);
            }
        });
        Set<Gav> brokenLibraries = new HashSet<>();
        for (Finding f : findings) {
            if (f.isBreak() && !f.origin().isProject()) {
                brokenLibraries.add(f.origin().library());
            }
        }

        Map<String, MediationIssue> out = new LinkedHashMap<>();
        for (DependencyTree.Edge edge : after.edges()) {
            TreeNode parent = edge.parent();
            TreeNode child = edge.child();
            if (parent == null || parent.isLoser() || !changed.contains(child.key())) {
                continue;
            }
            Gav resolvedGav = now.get(child.key());
            if (resolvedGav == null) {
                continue;
            }
            String requested = child.requestedVersion();
            String resolved = resolvedGav.version();
            if (requested.equals(resolved)) {
                continue;
            }
            MediationIssue issue = classify(parent.gav(), child.key(), requested, resolved,
                    child.key().equals(target), brokenLibraries.contains(parent.gav()));
            out.putIfAbsent(parent.gav() + "->" + child.key(), issue);
        }
        return new ArrayList<>(out.values());
    }

    private static MediationIssue classify(Gav library, String dependency, String requested, String resolved,
            boolean isTarget, boolean scannedAndBroken) {
        String name = library.artifactId() + " " + library.version();
        String dep = dependency.substring(dependency.indexOf(':') + 1);
        String base = name + " asks for " + dep + " " + requested + " but will get " + resolved;
        boolean downgrade = Versions.compare(resolved, requested) < 0;
        boolean major = Versions.isMajorChange(requested, resolved);
        MediationIssue.Risk risk;
        String why;
        if (scannedAndBroken) {
            risk = MediationIssue.Risk.BREAKS;
            why = base + ", and its code uses API that " + resolved + " no longer has (see the breaks above).";
        } else if (downgrade) {
            risk = MediationIssue.Risk.RISKY;
            why = base + ", which is OLDER than it was built for. It may call things that do not exist yet.";
        } else if (major && !isTarget) {
            risk = MediationIssue.Risk.RISKY;
            why = base + ", a different major version. Tremor did not scan this pair; check its release notes.";
        } else if (major) {
            risk = MediationIssue.Risk.LOW;
            why = base + ", a different major version. Tremor scanned its bytecode and found no break.";
        } else {
            risk = MediationIssue.Risk.LOW;
            why = base + ".";
        }
        return new MediationIssue(library, dependency, requested, resolved, risk, why);
    }
}
