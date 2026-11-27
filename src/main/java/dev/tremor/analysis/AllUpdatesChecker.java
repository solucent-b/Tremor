package dev.tremor.analysis;

import dev.tremor.project.DeclaredDependency;
import dev.tremor.project.ProjectModel;
import dev.tremor.resolve.MavenResolver;
import dev.tremor.resolve.ProjectLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** {@code --all-updates}: finds the newest version of every direct dependency and checks each upgrade. */
public final class AllUpdatesChecker {

    private final MavenResolver resolver;
    private final UpgradeChecker checker;

    public AllUpdatesChecker(MavenResolver resolver, UpgradeChecker checker) {
        this.resolver = resolver;
        this.checker = checker;
    }

    public List<UpdateRow> check(Path projectDir) {
        ProjectModel project = new ProjectLoader(resolver).load(projectDir);
        List<UpdateRow> rows = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (DeclaredDependency d : project.dependencies()) {
            if (d.version() == null || !seen.add(d.key()) || "system".equals(d.scope())) {
                continue;
            }
            try {
                Optional<String> latest = LatestVersion.pick(d.version(),
                        resolver.newerVersions(d.groupId(), d.artifactId(), d.version()));
                if (latest.isEmpty()) {
                    rows.add(new UpdateRow(d.groupId(), d.artifactId(), d.version(), null, null, null));
                    continue;
                }
                CheckResult result = checker.check(projectDir, project, d.groupId(), d.artifactId(), latest.get());
                rows.add(new UpdateRow(d.groupId(), d.artifactId(), result.from().version(), latest.get(), result,
                        null));
            } catch (RuntimeException e) {
                rows.add(new UpdateRow(d.groupId(), d.artifactId(), d.version(), null, null, e.getMessage()));
            }
        }
        return rows;
    }
}
