package dev.tremor.analysis;

import dev.tremor.diff.ChangeKind;
import dev.tremor.diff.Severity;
import dev.tremor.scan.Location;

/**
 * One concrete problem: a place in real code that uses something that changed.
 *
 * @param action what the code does, e.g. {@code calls Cache.getIfPresent(Object)}
 * @param reason what changed, e.g. {@code was removed}
 * @param consequence what happens, e.g. {@code NoSuchMethodError at runtime}; null for warnings
 * @param hint what to do about it, or null
 */
public record Finding(
        Severity severity,
        ChangeKind kind,
        Origin origin,
        Location location,
        String action,
        String reason,
        String consequence,
        String hint) {

    public boolean isBreak() {
        return severity == Severity.BREAKING;
    }

    /** {@code UserService.java:84 calls Cache.getIfPresent(Object), which was removed}. */
    public String sentence() {
        return location.shortForm() + " " + action + ", which " + reason;
    }
}
