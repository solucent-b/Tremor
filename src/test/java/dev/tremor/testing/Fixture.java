package dev.tremor.testing;

import dev.tremor.project.Gav;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds a small offline world for end-to-end tests: libraries compiled from source and published
 * to a {@link TestRepository}, and a project with a {@code pom.xml} and compiled {@code target/classes}.
 */
public final class Fixture {

    private final Path root;
    private final TestRepository repo;
    private int counter;

    public Fixture(Path root) {
        this.root = root;
        this.repo = new TestRepository(root.resolve("remote"));
    }

    public TestRepository repo() {
        return repo;
    }

    public Path cacheDir() {
        return root.resolve("cache");
    }

    /**
     * Compiles and publishes a library version.
     *
     * @param classpath jars the sources compile against (e.g. another library)
     * @param dependencies POM dependencies as {@code g:a:v}
     */
    public Path library(Gav gav, List<Path> classpath, List<String> dependencies, Map<String, String> sources) {
        Path jar = JavaCompilation.compileJar(root.resolve("build"), gav.artifactId() + "-" + gav.version()
                + "-" + (counter++), classpath, sources);
        repo.pom(gav, dependencies.toArray(String[]::new));
        repo.jar(gav, jar);
        return jar;
    }

    public Path library(Gav gav, Map<String, String> sources) {
        return library(gav, List.of(), List.of(), sources);
    }

    /** Writes a project with the given dependencies and compiles its sources against {@code classpath}. */
    public Path project(String name, List<String> dependencies, List<Path> classpath, Map<String, String> sources) {
        try {
            Path dir = Files.createDirectories(root.resolve(name));
            StringBuilder deps = new StringBuilder();
            for (String d : dependencies) {
                String[] p = d.split(":");
                deps.append("<dependency><groupId>").append(p[0]).append("</groupId><artifactId>").append(p[1])
                        .append("</artifactId><version>").append(p[2]).append("</version></dependency>\n");
            }
            Files.writeString(dir.resolve("pom.xml"), """
                    <project xmlns="http://maven.apache.org/POM/4.0.0">
                      <modelVersion>4.0.0</modelVersion>
                      <groupId>com.example</groupId><artifactId>%s</artifactId><version>1.0.0</version>
                      <dependencies>
                    %s  </dependencies>
                    </project>
                    """.formatted(name, deps));
            for (var e : sources.entrySet()) {
                Path src = dir.resolve("src/main/java/" + e.getKey().replace('.', '/') + ".java");
                Files.createDirectories(src.getParent());
                Files.writeString(src, e.getValue());
            }
            if (!sources.isEmpty()) {
                JavaCompilation.compile(dir.resolve("target/classes"), new ArrayList<>(classpath), sources);
            } else {
                Files.createDirectories(dir.resolve("target/classes"));
            }
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** CLI arguments that point Tremor at this fixture's repository and cache only. */
    public List<String> offlineArgs() {
        return List.of("--repository", repo.url(), "--no-central", "--cache-dir", cacheDir().toString());
    }
}
