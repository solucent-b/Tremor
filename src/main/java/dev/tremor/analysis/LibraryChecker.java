package dev.tremor.analysis;

import dev.tremor.bytecode.ApiModel;
import dev.tremor.cache.AnalysisCache;
import dev.tremor.diff.ApiDiff;
import dev.tremor.diff.ApiDiffer;
import dev.tremor.project.Gav;
import dev.tremor.resolve.DependencyTree;
import dev.tremor.resolve.JarPair;
import dev.tremor.resolve.MavenResolver;
import dev.tremor.resolve.ResolutionException;
import dev.tremor.resolve.TreeNode;
import dev.tremor.scan.ScanResult;
import dev.tremor.scan.UsageScanner;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Scans every other library in the (upgraded) dependency tree that declares the upgraded
 * dependency, to catch "library Y will crash because it was built for the old version".
 *
 * <p>Each library is compared against the version it declared, which is the version it was most
 * likely compiled against. Libraries are scanned in parallel and results are cached per jar.
 */
final class LibraryChecker {

    private final MavenResolver resolver;
    private final AnalysisCache cache;
    private final ApiModel newApi;
    private final List<String> notes;
    private final Comparison current;
    private final Map<String, Comparison> byVersion = new ConcurrentHashMap<>();

    LibraryChecker(MavenResolver resolver, AnalysisCache cache, JarPair jars, ApiModel currentApi, ApiModel newApi,
            ApiDiff currentDiff, List<String> notes) {
        this.resolver = resolver;
        this.cache = cache;
        this.newApi = newApi;
        this.notes = notes;
        this.current = new Comparison(currentApi, currentDiff, cache.sha256(jars.oldJar()));
        byVersion.put(jars.oldGav().version(), current);
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
        // Downloads go one at a time; the CPU-heavy scanning runs in parallel.
        List<Job> jobs = new ArrayList<>();
        for (var entry : dependents.entrySet()) {
            try {
                Path jar = resolver.resolveJar(entry.getKey());
                jobs.add(new Job(entry.getKey(), jar, comparisonFor(groupId, artifactId, entry.getValue())));
            } catch (ResolutionException | UncheckedIOException e) {
                notes.add("Could not scan " + entry.getKey() + ": " + e.getMessage());
            }
        }
        List<Outcome> outcomes = jobs.parallelStream().map(this::run).toList();
        List<Finding> findings = new ArrayList<>();
        for (Outcome o : outcomes) {
            if (o.error() != null) {
                notes.add("Could not scan " + o.library() + ": " + o.error());
            } else {
                findings.addAll(o.findings());
                scanned.add(o.library());
            }
        }
        return findings;
    }

    private Outcome run(Job job) {
        try {
            Comparison cmp = job.comparison();
            ScanResult scan = cache.scan(job.jar(), cmp.jarHash(),
                    () -> new UsageScanner(cmp.oldApi()::contains).scan(job.jar()));
            List<Finding> findings = new UsageMatcher(cmp.oldApi(), newApi, cmp.diff())
                    .match(scan, Origin.library(job.library()));
            return new Outcome(job.library(), findings, null);
        } catch (RuntimeException e) {
            return new Outcome(job.library(), List.of(), e.getMessage());
        }
    }

    private Comparison comparisonFor(String groupId, String artifactId, String requestedVersion) {
        return byVersion.computeIfAbsent(requestedVersion, v -> {
            try {
                Path jar = resolver.resolveJar(new Gav(groupId, artifactId, v));
                ApiModel api = cache.apiModel(jar);
                return new Comparison(api, ApiDiffer.diff(api, newApi), cache.sha256(jar));
            } catch (ResolutionException | UncheckedIOException e) {
                // A version range or an unpublished version: fall back to the project's current version.
                return current;
            }
        });
    }

    private record Comparison(ApiModel oldApi, ApiDiff diff, String jarHash) {
    }

    private record Job(Gav library, Path jar, Comparison comparison) {
    }

    private record Outcome(Gav library, List<Finding> findings, String error) {
    }
}
