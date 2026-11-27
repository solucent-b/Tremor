package dev.tremor.analysis;

import dev.tremor.project.Gav;

/** Whose code a finding is in: the user's project, or a library in its dependency tree. */
public record Origin(Gav library) {

    public static final Origin PROJECT = new Origin(null);

    public static Origin library(Gav gav) {
        return new Origin(gav);
    }

    public boolean isProject() {
        return library == null;
    }

    /** "your project" or {@code artifactId version}. */
    public String label() {
        return isProject() ? "your project" : library.artifactId() + " " + library.version();
    }
}
