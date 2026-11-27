package dev.tremor.analysis;

import dev.tremor.bytecode.ApiModel;
import dev.tremor.bytecode.ApiReader;
import dev.tremor.diff.ApiDiff;
import dev.tremor.diff.ApiDiffer;
import dev.tremor.diff.Severity;
import dev.tremor.project.Gav;
import dev.tremor.project.ProjectModel;
import dev.tremor.project.ProjectReadException;
import dev.tremor.resolve.DependencyTree;
import dev.tremor.resolve.DependencyTreeResolver;
import dev.tremor.resolve.JarPair;
import dev.tremor.resolve.MavenResolver;
import dev.tremor.resolve.ProjectLoader;
import dev.tremor.scan.ScanResult;
import dev.tremor.scan.UsageScanner;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Runs the whole check for one upgrade: resolve, download, diff, scan, match. */
public final class UpgradeChecker {

    /** Always shown: what static bytecode analysis cannot see. */
    public static final String REFLECTION_NOTE = "Tremor reads compiled bytecode. It cannot see reflection"
            + " (Class.forName, method names in strings), dependency injection or framework magic (e.g. Spring),"
            + " or behaviour changes inside methods. A clean result means no known break, not a guarantee.";

    private final MavenResolver resolver;
    private final Function<Path, List<Path>> classesLocator;

    public UpgradeChecker(MavenResolver resolver) {
        this(resolver, ProjectClasses::maven);
    }

    public UpgradeChecker(MavenResolver resolver, Function<Path, List<Path>> classesLocator) {
        this.resolver = resolver;
        this.classesLocator = classesLocator;
    }

    public CheckResult check(Path projectDir, String groupId, String artifactId, String toVersion) {
        ProjectModel project = new ProjectLoader(resolver).load(projectDir);
        return check(projectDir, project, groupId, artifactId, toVersion);
    }

    public CheckResult check(Path projectDir, ProjectModel project, String groupId, String artifactId,
            String toVersion) {
        List<String> notes = new ArrayList<>(project.warnings());
        DependencyTree before = new DependencyTreeResolver(resolver).resolve(project);
        Gav from = before.selected(groupId, artifactId).orElseThrow(() -> new ProjectReadException(
                groupId + ":" + artifactId + " is not in the dependency tree of " + project.gav()
                        + ". Check the spelling, or run 'mvn dependency:tree' to see what the project uses."));
        Gav to = new Gav(groupId, artifactId, toVersion);
        List<Path> classes = classesLocator.apply(projectDir);
        if (from.version().equals(toVersion)) {
            notes.add("The project already uses " + from + "; nothing to check.");
            return new CheckResult(projectDir, project.gav(), from, to, List.of(), List.of(),
                    new CheckResult.ApiChangeCounts(0, 0), List.of(), notes);
        }

        JarPair jars = JarPair.fetch(resolver, groupId, artifactId, from.version(), toVersion);
        ApiModel oldApi = ApiReader.read(jars.oldJar());
        ApiModel newApi = ApiReader.read(jars.newJar());
        ApiDiff diff = ApiDiffer.diff(oldApi, newApi);
        UsageMatcher matcher = new UsageMatcher(oldApi, newApi, diff);
        UsageScanner scanner = new UsageScanner(oldApi::contains);

        ScanResult projectScan = scanner.scan(classes);
        List<Finding> findings = new ArrayList<>(matcher.match(projectScan, Origin.PROJECT));
        projectScan.problems().forEach(p -> notes.add("Skipped an unreadable class: " + p));

        DependencyTree after = new DependencyTreeResolver(resolver)
                .resolveUpgraded(project, groupId, artifactId, toVersion);
        List<Gav> scanned = new ArrayList<>();
        findings.addAll(new LibraryChecker(resolver, from.version(), oldApi, newApi, diff, notes)
                .check(after, groupId, artifactId, scanned));
        List<MediationIssue> mediation = MediationChecker.check(before, after, groupId, artifactId, findings);

        skippedNote(oldApi, newApi, notes);
        notes.add(REFLECTION_NOTE);
        return new CheckResult(projectDir, project.gav(), from, to, findings, mediation,
                new CheckResult.ApiChangeCounts(diff.count(Severity.BREAKING), diff.count(Severity.WARNING)),
                scanned, notes);
    }

    private static void skippedNote(ApiModel oldApi, ApiModel newApi, List<String> notes) {
        int skipped = oldApi.problems().size() + newApi.problems().size();
        if (skipped > 0) {
            notes.add(skipped + " class file(s) in the dependency could not be read and were skipped.");
        }
    }
}
