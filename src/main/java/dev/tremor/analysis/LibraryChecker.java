package dev.tremor.analysis;

import dev.tremor.bytecode.ApiModel;
import dev.tremor.bytecode.ApiReader;
import dev.tremor.diff.ApiDiff;
import dev.tremor.diff.ApiDiffer;
import dev.tremor.project.Gav;
import dev.tremor.resolve.DependencyTree;
import dev.tremor.resolve.MavenResolver;
import dev.tremor.resolve.ResolutionException;
import dev.tremor.resolve.TreeNode;
import dev.tremor.scan.ScanResult;
import dev.tremor.scan.UsageScanner;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Scans every other library in the (upgraded) dependency tree that declares the upgraded
 * dependency, to catch "library Y will crash because it was built for the old version".
 *
 * <p>Each library is compared against the version it declared, which is the version it was most
 * likely compiled against.
 */
final class LibraryChecker {

    private final MavenResolver resolver;
    private final ApiModel currentApi;
    private final ApiModel newApi;
    private final ApiDiff currentDiff;
    private final List<String> notes;
    private final Map<String, Comparison> byVersion = new HashMap<>();

    LibraryChecker(MavenResolver resolver, String currentVersion, ApiModel currentApi, ApiModel newApi, ApiDiff currentDiff,
            List<String> notes) {
        this.resolver = resolver;
        this.currentApi = currentApi;
        this.newApi = newApi;
        this.currentDiff = currentDiff;
        this.notes = notes;
        byVersion.put(currentVersion, new Comparison(currentApi, currentDiff));
    }

    List<Finding> check(DependencyTree upgraded, String groupId, String artifactId, List<Gav> scanned) {
        // One entry per library, remembering which version of the target it asked for.
        Map<Gav, String> dependents = new LinkedHashMap<>();
        for (DependencyTree.Edge edge : upgraded.edgesTo(groupId, artifactId)) {
            TreeNode parent = edge.parent();
            if (parent != null && !parent.isLoser()) {
                dependents.putIfAbsent(parent.gav(), edge.child().requestedVersion());
            }
        }
        List<Finding> findings = new ArrayList<>();
        for (var entry : dependents.entrySet()) {
            Gav library = entry.getKey();
            try {
                Path jar = resolver.resolveJar(library);
                Comparison cmp = comparisonFor(groupId, artifactId, entry.getValue());
                ScanResult scan = new UsageScanner(cmp.oldApi()::contains).scan(jar);
                findings.addAll(new UsageMatcher(cmp.oldApi(), newApi, cmp.diff()).match(scan, Origin.library(library)));
                scanned.add(library);
            } catch (ResolutionException | java.io.UncheckedIOException e) {
                notes.add("Could not scan " + library + ": " + e.getMessage());
            }
        }
        return findings;
    }

    private Comparison comparisonFor(String groupId, String artifactId, String requestedVersion) {
        return byVersion.computeIfAbsent(requestedVersion, v -> {
            try {
                ApiModel api = ApiReader.read(resolver.resolveJar(new Gav(groupId, artifactId, v)));
                return new Comparison(api, ApiDiffer.diff(api, newApi));
            } catch (ResolutionException | java.io.UncheckedIOException e) {
                // A version range or an unpublished version: fall back to the project's current version.
                return new Comparison(currentApi, currentDiff);
            }
        });
    }

    private record Comparison(ApiModel oldApi, ApiDiff diff) {
    }
}
