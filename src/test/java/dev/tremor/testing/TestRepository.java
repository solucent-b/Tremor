package dev.tremor.testing;

import dev.tremor.project.Gav;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

/** A throwaway file-based Maven repository for offline tests. */
public final class TestRepository {

    private final Path root;
    private final Map<String, TreeSet<String>> versions = new TreeMap<>();

    public TestRepository(Path root) {
        this.root = root;
    }

    public String url() {
        return root.toUri().toString();
    }

    /** Publishes a POM with the given dependency coordinates ({@code g:a:v} or {@code g:a:v:scope}). */
    public TestRepository pom(Gav gav, String... dependencies) {
        StringBuilder deps = new StringBuilder();
        for (String d : dependencies) {
            String[] p = d.split(":");
            deps.append("<dependency><groupId>").append(p[0]).append("</groupId><artifactId>").append(p[1])
                    .append("</artifactId><version>").append(p[2]).append("</version>");
            if (p.length > 3) {
                deps.append("<scope>").append(p[3]).append("</scope>");
            }
            deps.append("</dependency>");
        }
        return rawPom(gav, "<dependencies>" + deps + "</dependencies>");
    }

    /** Publishes a POM whose body (inside {@code <project>}, after the coordinates) is given verbatim. */
    public TestRepository rawPom(Gav gav, String body) {
        String xml = """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId><artifactId>%s</artifactId><version>%s</version>
                  %s
                </project>
                """.formatted(gav.groupId(), gav.artifactId(), gav.version(), body);
        write(file(gav, "pom"), xml.getBytes());
        versions.computeIfAbsent(gav.groupId() + ":" + gav.artifactId(), k -> new TreeSet<>()).add(gav.version());
        writeMetadata(gav.groupId(), gav.artifactId());
        return this;
    }

    /** Publishes a jar built from the given class files (internal name to bytes). */
    public TestRepository jar(Gav gav, Map<String, byte[]> classes) {
        Path jar = file(gav, "jar");
        try {
            Files.createDirectories(jar.getParent());
            try (OutputStream out = Files.newOutputStream(jar); JarOutputStream zip = new JarOutputStream(out)) {
                for (var e : classes.entrySet()) {
                    zip.putNextEntry(new ZipEntry(e.getKey() + ".class"));
                    zip.write(e.getValue());
                    zip.closeEntry();
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this;
    }

    /** Publishes an existing jar file. */
    public TestRepository jar(Gav gav, Path jarFile) {
        try {
            Path target = file(gav, "jar");
            Files.createDirectories(target.getParent());
            Files.copy(jarFile, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this;
    }

    /** Publishes a POM and an empty jar. */
    public TestRepository library(Gav gav, String... dependencies) {
        pom(gav, dependencies);
        return jar(gav, Map.of());
    }

    private Path file(Gav gav, String ext) {
        return root.resolve(gav.groupId().replace('.', '/')).resolve(gav.artifactId()).resolve(gav.version())
                .resolve(gav.artifactId() + "-" + gav.version() + "." + ext);
    }

    private void writeMetadata(String groupId, String artifactId) {
        List<String> vs = new ArrayList<>(versions.get(groupId + ":" + artifactId));
        StringBuilder xml = new StringBuilder("<metadata><groupId>").append(groupId).append("</groupId><artifactId>")
                .append(artifactId).append("</artifactId><versioning><versions>");
        vs.forEach(v -> xml.append("<version>").append(v).append("</version>"));
        xml.append("</versions></versioning></metadata>");
        write(root.resolve(groupId.replace('.', '/')).resolve(artifactId).resolve("maven-metadata.xml"),
                xml.toString().getBytes());
    }

    private static void write(Path file, byte[] bytes) {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
