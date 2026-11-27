package dev.tremor.cli;

import java.util.Objects;

/** A {@code groupId:artifactId} pair identifying the dependency to upgrade. */
public record DependencyCoordinate(String groupId, String artifactId) {

    public DependencyCoordinate {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(artifactId, "artifactId");
    }

    /** Parses {@code groupId:artifactId}. */
    public static DependencyCoordinate parse(String text) {
        String[] parts = text.trim().split(":");
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException(
                    "Expected groupId:artifactId (e.g. com.google.guava:guava) but got '" + text + "'");
        }
        return new DependencyCoordinate(parts[0].trim(), parts[1].trim());
    }

    @Override
    public String toString() {
        return groupId + ":" + artifactId;
    }
}
