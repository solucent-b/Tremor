package dev.tremor.resolve;

import dev.tremor.project.Gav;
import java.nio.file.Path;
import java.util.List;

/** The old and new jar of the dependency being upgraded. */
public record JarPair(Gav oldGav, Path oldJar, Gav newGav, Path newJar) {

    /** Downloads both jars, with a helpful message when the target version does not exist. */
    public static JarPair fetch(MavenResolver resolver, String groupId, String artifactId,
            String oldVersion, String newVersion) {
        Gav oldGav = new Gav(groupId, artifactId, oldVersion);
        Gav newGav = new Gav(groupId, artifactId, newVersion);
        Path oldJar = resolver.resolveJar(oldGav);
        Path newJar;
        try {
            newJar = resolver.resolveJar(newGav);
        } catch (ResolutionException e) {
            throw new ResolutionException(missingVersionMessage(resolver, newGav, oldVersion), e);
        }
        return new JarPair(oldGav, oldJar, newGav, newJar);
    }

    private static String missingVersionMessage(MavenResolver resolver, Gav gav, String oldVersion) {
        String message = "Could not download " + gav + ". Check that version " + gav.version() + " exists.";
        try {
            List<String> newer = resolver.newerVersions(gav.groupId(), gav.artifactId(), oldVersion);
            if (!newer.isEmpty()) {
                List<String> latest = newer.subList(Math.max(0, newer.size() - 5), newer.size());
                message += " Newest published versions: " + String.join(", ", latest) + ".";
            }
        } catch (ResolutionException ignored) {
            // Best effort only.
        }
        return message;
    }
}
