package dev.tremor.analysis;

import org.apache.maven.artifact.versioning.ComparableVersion;

/** Version comparison using Maven's own ordering rules. */
public final class Versions {

    private Versions() {
    }

    public static int compare(String a, String b) {
        return new ComparableVersion(a).compareTo(new ComparableVersion(b));
    }

    /** The leading number of a version, e.g. 33 for {@code 33.0.0-jre}; -1 if there is none. */
    public static int major(String version) {
        int end = 0;
        while (end < version.length() && Character.isDigit(version.charAt(end))) {
            end++;
        }
        return end == 0 ? -1 : Integer.parseInt(version.substring(0, Math.min(end, 9)));
    }

    public static boolean isMajorChange(String from, String to) {
        int a = major(from);
        int b = major(to);
        return a >= 0 && b >= 0 && a != b;
    }

    /** True for versions that look like pre-releases (alpha, beta, rc, milestones, snapshots). */
    public static boolean isPreRelease(String version) {
        String v = version.toLowerCase(java.util.Locale.ROOT);
        return v.contains("snapshot") || v.matches(".*[.-](alpha|beta|rc|cr|m|ea|preview|pre|dev)[.-]?\\d*.*")
                || v.matches(".*\\d(a|b|rc|m)\\d+.*");
    }
}
