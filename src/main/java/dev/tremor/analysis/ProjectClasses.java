package dev.tremor.analysis;

import dev.tremor.project.ProjectReadException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Finds a project's compiled classes (main and test). */
public final class ProjectClasses {

    private ProjectClasses() {
    }

    /** {@code target/classes} and, if present, {@code target/test-classes}. */
    public static List<Path> maven(Path projectDir) {
        Path main = projectDir.resolve("target/classes");
        if (!Files.isDirectory(main)) {
            throw new ProjectReadException("No compiled classes found in " + main.toAbsolutePath().normalize()
                    + ". Tremor reads your compiled code: run 'mvn compile' (or 'mvn test-compile') first.");
        }
        List<Path> out = new ArrayList<>();
        out.add(main);
        Path test = projectDir.resolve("target/test-classes");
        if (Files.isDirectory(test)) {
            out.add(test);
        }
        return out;
    }
}
