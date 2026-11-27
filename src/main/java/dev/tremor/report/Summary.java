package dev.tremor.report;

import dev.tremor.analysis.CheckResult;
import dev.tremor.analysis.MediationIssue;
import java.util.Set;
import java.util.stream.Collectors;

/** Counts shown at the end of every report. */
public record Summary(int breaks, int warnings, int files, int libraries, int riskyVersionChanges, String verdict) {

    public static Summary of(CheckResult r) {
        int breaks = r.breaks().size();
        int warnings = r.warnings().size();
        Set<String> files = r.findings().stream().filter(f -> f.origin().isProject())
                .map(f -> f.location().sourcePath()).collect(Collectors.toSet());
        Set<String> libraries = r.findings().stream().filter(f -> !f.origin().isProject())
                .map(f -> f.origin().label()).collect(Collectors.toSet());
        r.mediation().stream().filter(m -> m.risk() != MediationIssue.Risk.LOW)
                .forEach(m -> libraries.add(m.library().artifactId() + " " + m.library().version()));
        int risky = (int) r.mediation().stream().filter(m -> m.risk() == MediationIssue.Risk.RISKY).count();
        String verdict = r.hasBreaks() ? "breaks" : warnings > 0 || risky > 0 ? "warnings" : "safe";
        return new Summary(breaks, warnings, files.size(), libraries.size(), risky, verdict);
    }

    /** {@code 3 breaks, 2 warnings across 2 files and 1 library}. */
    public String line() {
        String counts = plural(breaks, "break") + ", " + plural(warnings, "warning");
        if (files == 0 && libraries == 0) {
            return counts;
        }
        StringBuilder where = new StringBuilder(" across ");
        if (files > 0) {
            where.append(plural(files, "file"));
        }
        if (files > 0 && libraries > 0) {
            where.append(" and ");
        }
        if (libraries > 0) {
            where.append(libraries).append(libraries == 1 ? " library" : " libraries");
        }
        return counts + where;
    }

    static String plural(long n, String word) {
        return n + " " + word + (n == 1 ? "" : "s");
    }
}
