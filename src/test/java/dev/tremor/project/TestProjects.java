package dev.tremor.project;

import java.net.URISyntaxException;
import java.nio.file.Path;

/** Locates fixture projects under {@code src/test/resources/projects}. */
public final class TestProjects {

    private TestProjects() {
    }

    public static Path path(String name) {
        try {
            return Path.of(TestProjects.class.getResource("/projects/" + name).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
