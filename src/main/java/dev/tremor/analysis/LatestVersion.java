package dev.tremor.analysis;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Picks the newest stable version to upgrade to, staying on the same "flavour" (e.g. {@code -jre}). */
public final class LatestVersion {

    private static final Pattern NUMERIC_PREFIX = Pattern.compile("^[0-9]+(\\.[0-9]+)*");
    private static final List<String> RELEASE_MARKERS = List.of("", ".final", "-final", ".release", "-release", "-ga",
            ".ga");

    private LatestVersion() {
    }

    public static Optional<String> pick(String current, List<String> candidates) {
        String flavour = flavour(current);
        return candidates.stream()
                .filter(v -> !Versions.isPreRelease(v))
                .filter(v -> sameFlavour(flavour, flavour(v)))
                .filter(v -> Versions.compare(v, current) > 0)
                .max(Comparator.comparing(v -> new org.apache.maven.artifact.versioning.ComparableVersion(v)));
    }

    /** The part after the numbers: {@code -jre} for {@code 33.0.0-jre}, empty for {@code 2.17.1}. */
    static String flavour(String version) {
        Matcher m = NUMERIC_PREFIX.matcher(version);
        return (m.find() ? version.substring(m.end()) : version).toLowerCase(Locale.ROOT);
    }

    private static boolean sameFlavour(String a, String b) {
        if (a.equals(b)) {
            return true;
        }
        return RELEASE_MARKERS.contains(a) && RELEASE_MARKERS.contains(b);
    }
}
