package dev.tremor.cache;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tremor.bytecode.ApiModel;
import dev.tremor.scan.ScanResult;
import dev.tremor.scan.UsageScanner;
import dev.tremor.testing.JavaCompilation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnalysisCacheTest {

    @TempDir
    Path temp;

    private Path jar() {
        return JavaCompilation.compileJar(temp, "lib", List.of(), Map.of(
                "lib.A", "package lib; public class A { public static void m() {} }",
                "lib.B", "package lib; public class B { void x() { A.m(); } }"));
    }

    @Test
    void apiModelSurvivesARoundTripThroughTheCache() {
        Path jar = jar();
        AnalysisCache cache = AnalysisCache.in(temp.resolve("cache"));

        ApiModel first = cache.apiModel(jar);
        ApiModel second = AnalysisCache.in(temp.resolve("cache")).apiModel(jar);

        assertThat(second).isNotSameAs(first);
        assertThat(second.classes()).isEqualTo(first.classes());
        assertThat(second.get("lib/A").methods()).containsKey("m()V");
    }

    @Test
    void scanIsComputedOnceThenServedFromDisk() {
        Path jar = jar();
        AtomicInteger computed = new AtomicInteger();
        java.util.function.Supplier<ScanResult> compute = () -> {
            computed.incrementAndGet();
            return new UsageScanner(n -> n.startsWith("lib/")).scan(jar);
        };

        ScanResult a = AnalysisCache.in(temp.resolve("cache")).scan(jar, "k", compute);
        ScanResult b = AnalysisCache.in(temp.resolve("cache")).scan(jar, "k", compute);

        assertThat(computed).hasValue(1);
        assertThat(b.usages()).isEqualTo(a.usages());
    }

    @Test
    void corruptEntriesAreRebuilt() throws Exception {
        Path jar = jar();
        AnalysisCache.in(temp.resolve("cache")).apiModel(jar);
        try (Stream<Path> files = Files.walk(temp.resolve("cache"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".bin.gz")).toList()) {
                Files.write(f, new byte[] {1, 2, 3});
            }
        }

        ApiModel rebuilt = AnalysisCache.in(temp.resolve("cache")).apiModel(jar);

        assertThat(rebuilt.classes()).containsKey("lib/A");
    }

    @Test
    void sameContentSameKey(@TempDir Path other) throws Exception {
        Path jar = jar();
        Path copy = Files.copy(jar, other.resolve("copy.jar"));
        AnalysisCache cache = AnalysisCache.disabled();

        assertThat(cache.sha256(copy)).isEqualTo(cache.sha256(jar)).hasSize(64);
    }
}
