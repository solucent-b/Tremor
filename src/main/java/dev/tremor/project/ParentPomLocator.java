package dev.tremor.project;

import java.nio.file.Path;
import java.util.Optional;

/** Finds a parent POM file that is not next to the project (e.g. one in a remote repository). */
@FunctionalInterface
public interface ParentPomLocator {

    ParentPomLocator NONE = gav -> Optional.empty();

    Optional<Path> locate(Gav parent);
}
