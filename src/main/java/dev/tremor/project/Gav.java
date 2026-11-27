package dev.tremor.project;

/** A Maven {@code groupId:artifactId:version} triple. */
public record Gav(String groupId, String artifactId, String version) {

    @Override
    public String toString() {
        return groupId + ":" + artifactId + ":" + version;
    }
}
