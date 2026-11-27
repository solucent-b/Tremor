package dev.tremor.analysis;

/**
 * One line of the {@code --all-updates} table.
 *
 * @param latest newest stable version, or null when the dependency is up to date
 * @param result the full check, or null when up to date or when the check failed
 * @param error why the check failed, or null
 */
public record UpdateRow(String groupId, String artifactId, String current, String latest, CheckResult result,
        String error) {

    public enum Verdict { SAFE, RISKY, BREAKS, UP_TO_DATE, ERROR }

    public String key() {
        return groupId + ":" + artifactId;
    }

    public Verdict verdict() {
        if (error != null) {
            return Verdict.ERROR;
        }
        if (latest == null || result == null) {
            return Verdict.UP_TO_DATE;
        }
        if (result.hasBreaks()) {
            return Verdict.BREAKS;
        }
        boolean risky = !result.warnings().isEmpty() || result.mediation().stream()
                .anyMatch(m -> m.risk() == MediationIssue.Risk.RISKY);
        return risky ? Verdict.RISKY : Verdict.SAFE;
    }
}
