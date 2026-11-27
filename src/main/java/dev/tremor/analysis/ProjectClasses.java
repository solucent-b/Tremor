package dev.tremor.analysis;

import dev.tremor.project.GradleBuildReader;
import dev.tremor.project.ProjectReadException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Finds a project's compiled classes (main and test) for Maven and Gradle layouts. */
public final class ProjectClasses {

    private ProjectClasses() {
    }

    /** Maven ({@code target/classes}) or Gradle ({@code build/classes/<language>/main}). */
    public static List<Path> forProject(Path projectDir) {
        if (!Files.isRegularFile(projectDir.resolve("pom.xml")) && GradleBuildReader.isGradleProject(projectDir)) {
            return gradle(projectDir);
        }
        return maven(projectDir);
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

    /** Every {@code build/classes/<language>/<sourceSet>} directory (java, kotlin, groovy, scala). */
    public static List<Path> gradle(Path projectDir) {
        Path classes = projectDir.resolve("build/classes");
        List<Path> out = new ArrayList<>();
        if (Files.isDirectory(classes)) {
            try (Stream<Path> languages = Files.list(classes)) {
                for (Path language : languages.filter(Files::isDirectory).sorted().toList()) {
                    try (Stream<Path> sets = Files.list(language)) {
                        sets.filter(Files::isDirectory).sorted().forEach(out::add);
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        if (out.isEmpty()) {
            throw new ProjectReadException("No compiled classes found in " + classes.toAbsolutePath().normalize()
                    + ". Tremor reads your compiled code: run './gradlew classes' (or 'testClasses') first.");
        }
        return out;
    }
}
