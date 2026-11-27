package dev.tremor.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tremor.project.Gav;
import dev.tremor.resolve.MavenResolver;
import dev.tremor.resolve.ResolverConfig;
import dev.tremor.testing.Fixture;
import dev.tremor.testing.JavaCompilation;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A project with 150+ dependencies must be checked in well under 20 seconds once the cache is warm. */
class LargeProjectSpeedTest {

    static final int LIBRARIES = 160;

    @TempDir
    Path temp;

    @Test
    void warmCheckOfAProjectWith160DependenciesIsFast() throws Exception {
        Fixture fixture = new Fixture(temp);
        StringBuilder api = new StringBuilder("package t; public class Api {\n");
        for (int i = 0; i < 200; i++) {
            api.append("  public static int m").append(i).append("(int x) { return x; }\n");
        }
        Path v1 = fixture.library(new Gav("org.t", "target", "1.0"), Map.of("t.Api", api + "}"));
        fixture.library(new Gav("org.t", "target", "2.0"), Map.of("t.Api", api.toString()
                .replace("  public static int m7(int x) { return x; }\n", "") + "}"));

        StringBuilder user = new StringBuilder("package u; public class User {\n");
        for (int i = 0; i < 50; i++) {
            user.append("  public int c").append(i).append("(int x) { return t.Api.m").append(i).append("(x); }\n");
        }
        Path userJar = JavaCompilation.compileJar(temp.resolve("build"), "user", List.of(v1),
                Map.of("u.User", user + "}"));

        List<String> deps = new ArrayList<>(List.of("org.t:target:1.0"));
        for (int i = 0; i < LIBRARIES; i++) {
            Gav gav = new Gav("org.many", "lib" + i, "1.0");
            fixture.repo().pom(gav, "org.t:target:1.0");
            fixture.repo().jar(gav, variant(userJar, i));
            deps.add(gav.toString());
        }
        Path project = fixture.project("app", deps, List.of(v1), Map.of("app.Main",
                "package app; public class Main { int a() { return t.Api.m1(1); } }"));

        CheckResult cold = check(fixture, project);
        long start = System.nanoTime();
        CheckResult warm = check(fixture, project);
        double seconds = (System.nanoTime() - start) / 1e9;

        assertThat(cold.scannedLibraries()).hasSize(LIBRARIES);
        assertThat(warm.scannedLibraries()).hasSize(LIBRARIES);
        assertThat(warm.breaks()).hasSize(LIBRARIES); // every library calls the removed m7
        assertThat(warm.breaks()).isEqualTo(cold.breaks());
        System.out.printf("Warm check of %d libraries took %.2fs%n", LIBRARIES, seconds);
        assertThat(seconds).isLessThan(20.0);
    }

    private static CheckResult check(Fixture fixture, Path project) {
        try (MavenResolver resolver = new MavenResolver(
                new ResolverConfig(fixture.cacheDir(), List.of(fixture.repo().url())))) {
            return new UpgradeChecker(resolver).check(project, "org.t", "target", "2.0");
        }
    }

    /** A copy of the jar with a unique extra entry, so every library has a different hash. */
    private Path variant(Path jar, int i) throws IOException {
        Path out = temp.resolve("variants/lib" + i + ".jar");
        Files.createDirectories(out.getParent());
        try (JarFile in = new JarFile(jar.toFile()); OutputStream os = Files.newOutputStream(out);
                JarOutputStream zip = new JarOutputStream(os)) {
            var entries = in.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                zip.putNextEntry(new ZipEntry(e.getName()));
                zip.write(in.getInputStream(e).readAllBytes());
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("marker-" + i + ".txt"));
            zip.write(("library " + i).getBytes());
            zip.closeEntry();
        }
        return out;
    }
}
