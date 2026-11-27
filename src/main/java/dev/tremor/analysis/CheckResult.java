package dev.tremor.analysis;

import dev.tremor.diff.Severity;
import dev.tremor.project.Gav;
import java.nio.file.Path;
import java.util.List;

/**
 * The outcome of checking one upgrade.
 *
 * @param projectDir the project's root directory
 * @param project the project's own coordinates
 * @param from the version in use today
 * @param to the version being checked
 * @param findings breaks and warnings in real code
 * @param mediation libraries that will get a version they did not ask for
 * @param apiChanges how many breaking and warning-level changes the library made overall
 * @param scannedLibraries libraries in the tree that use the dependency and were scanned
 * @param notes things the user should know about the analysis itself (limits, skipped files)
 */
public record CheckResult(
        Path projectDir,
        Gav project,
        Gav from,
        Gav to,
        List<Finding> findings,
        List<MediationIssue> mediation,
        ApiChangeCounts apiChanges,
        List<Gav> scannedLibraries,
        List<String> notes) {

    public CheckResult {
        findings = List.copyOf(findings);
        mediation = List.copyOf(mediation);
        scannedLibraries = List.copyOf(scannedLibraries);
        notes = List.copyOf(notes);
    }

    public List<Finding> breaks() {
        return findings.stream().filter(Finding::isBreak).toList();
    }

    public List<Finding> warnings() {
        return findings.stream().filter(f -> f.severity() == Severity.WARNING).toList();
    }

    public boolean hasBreaks() {
        return !breaks().isEmpty() || mediation.stream().anyMatch(MediationIssue::isBreak);
    }

    /** Totals of API changes in the library, whether or not this project uses them. */
    public record ApiChangeCounts(long breaking, long warnings) {
    }
}
