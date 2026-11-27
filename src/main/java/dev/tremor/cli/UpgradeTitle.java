package dev.tremor.cli;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The dependency upgrade described by a Dependabot or Renovate pull request title.
 *
 * @param from the old version, or null when the title does not say (Renovate)
 * @param directory the sub-directory Dependabot names with "in /path", or null
 */
public record UpgradeTitle(String dependency, String from, String to, String directory) {

    // Dependabot: "Bump com.google.guava:guava from 32.1.0-jre to 33.0.0-jre in /backend"
    private static final Pattern DEPENDABOT = Pattern.compile(
            "(?i)\\bbump\\s+([\\w.-]+:[\\w.-]+)\\s+from\\s+(\\S+)\\s+to\\s+(\\S+?)(?:\\s+in\\s+(\\S+))?\\s*(?:\\(#\\d+\\))?$");
    // Renovate: "Update dependency com.google.guava:guava to v33.0.0-jre"
    private static final Pattern RENOVATE = Pattern.compile(
            "(?i)\\bupdate\\s+(?:dependency\\s+)?([\\w.-]+:[\\w.-]+)\\s+to\\s+v?(\\S+?)\\s*(?:\\(#\\d+\\))?$");

    public static Optional<UpgradeTitle> parse(String title) {
        if (title == null) {
            return Optional.empty();
        }
        String t = title.strip();
        Matcher m = DEPENDABOT.matcher(t);
        if (m.find()) {
            return Optional.of(new UpgradeTitle(m.group(1), m.group(2), clean(m.group(3)), m.group(4)));
        }
        m = RENOVATE.matcher(t);
        if (m.find()) {
            return Optional.of(new UpgradeTitle(m.group(1), null, clean(m.group(2)), null));
        }
        return Optional.empty();
    }

    private static String clean(String version) {
        return version.replaceAll("[.,;:)\\]]+$", "");
    }
}
