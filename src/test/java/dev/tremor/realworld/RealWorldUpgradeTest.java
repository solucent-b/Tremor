package dev.tremor.realworld;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tremor.analysis.CheckResult;
import dev.tremor.analysis.Finding;
import dev.tremor.analysis.UpgradeChecker;
import dev.tremor.bytecode.ApiModel;
import dev.tremor.bytecode.ApiReader;
import dev.tremor.diff.ApiDiffer;
import dev.tremor.diff.ChangeKind;
import dev.tremor.project.Gav;
import dev.tremor.project.PomReader;
import dev.tremor.project.ProjectModel;
import dev.tremor.resolve.DependencyTree;
import dev.tremor.resolve.DependencyTreeResolver;
import dev.tremor.resolve.MavenResolver;
import dev.tremor.resolve.ResolverConfig;
import dev.tremor.testing.JavaCompilation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs Tremor on real upgrades from Maven Central. Needs network; run with {@code mvn verify -Pnetwork}. */
@Tag("network")
class RealWorldUpgradeTest {

    static MavenResolver resolver;

    @TempDir
    Path temp;

    @BeforeAll
    static void open() {
        resolver = new MavenResolver(ResolverConfig.defaults());
    }

    @AfterAll
    static void close() {
        resolver.close();
    }

    /** Writes a one-dependency project and compiles its sources against the real jars. */
    private Path project(String g, String a, String v, String className, String source) throws Exception {
        Path dir = Files.createDirectories(temp.resolve(a + "-app"));
        Files.writeString(dir.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId><artifactId>app</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency><groupId>%s</groupId><artifactId>%s</artifactId><version>%s</version></dependency>
                  </dependencies>
                </project>
                """.formatted(g, a, v));
        Path src = dir.resolve("src/main/java/" + className.replace('.', '/') + ".java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, source);
        ProjectModel model = new PomReader().read(dir);
        DependencyTree tree = new DependencyTreeResolver(resolver).resolve(model);
        List<Path> classpath = new ArrayList<>();
        for (Gav gav : tree.selected().values()) {
            classpath.add(resolver.resolveJar(gav));
        }
        JavaCompilation.compile(dir.resolve("target/classes"), classpath, Map.of(className, source));
        return dir;
    }

    private static List<String> sentences(List<Finding> findings) {
        return findings.stream().map(Finding::sentence).toList();
    }

    @Test
    void guava20To33() throws Exception {
        Path app = project("com.google.guava", "guava", "20.0", "com.example.UserService", """
                package com.example;

                import com.google.common.base.Objects;
                import com.google.common.base.Strings;
                import com.google.common.cache.Cache;
                import com.google.common.cache.CacheBuilder;
                import com.google.common.util.concurrent.Futures;
                import com.google.common.util.concurrent.ListenableFuture;
                import com.google.common.util.concurrent.MoreExecutors;
                import java.util.concurrent.Executor;

                public class UserService {
                    private final Cache<String, String> cache = CacheBuilder.newBuilder().maximumSize(100).build();

                    public String describe(String name) {
                        if (Strings.isNullOrEmpty(name)) {
                            return "nobody";
                        }
                        return Objects.toStringHelper(this).add("name", name).toString();
                    }

                    public Executor executor() {
                        return MoreExecutors.sameThreadExecutor();
                    }

                    public ListenableFuture<Integer> length(ListenableFuture<String> in) {
                        return Futures.transform(in, (com.google.common.base.Function<String, Integer>) String::length);
                    }

                    public String cached(String key) {
                        return cache.getIfPresent(key);
                    }
                }
                """);

        CheckResult r = new UpgradeChecker(resolver).check(app, "com.google.guava", "guava", "33.0.0-jre");

        assertThat(r.from().version()).isEqualTo("20.0");
        assertThat(sentences(r.breaks())).contains(
                "UserService.java:19 calls Objects.toStringHelper(Object), which was removed",
                "UserService.java:23 calls MoreExecutors.sameThreadExecutor(), which was removed",
                "UserService.java:27 calls Futures.transform(ListenableFuture, Function), which no longer exists"
                        + " with these parameters");
        assertThat(r.breaks()).filteredOn(b -> b.action().contains("toStringHelper")).first()
                .satisfies(b -> assertThat(b.hint()).contains("MoreObjects.toStringHelper(Object)"));
        assertThat(r.breaks()).noneMatch(b -> b.action().contains("isNullOrEmpty") || b.action().contains("getIfPresent"));
        assertThat(r.hasBreaks()).isTrue();
    }

    @Test
    void jacksonDatabind29To217() throws Exception {
        Path app = project("com.fasterxml.jackson.core", "jackson-databind", "2.9.10", "com.example.Json", """
                package com.example;

                import com.fasterxml.jackson.databind.JsonNode;
                import com.fasterxml.jackson.databind.ObjectMapper;
                import com.fasterxml.jackson.databind.PropertyNamingStrategy;
                import java.util.Map;

                public class Json {
                    private final ObjectMapper mapper = new ObjectMapper();

                    public Json() {
                        mapper.setPropertyNamingStrategy(PropertyNamingStrategy.SNAKE_CASE);
                    }

                    public Map<?, ?> parse(String json) throws Exception {
                        return mapper.readValue(json, Map.class);
                    }

                    public String field(String json, String name) throws Exception {
                        JsonNode node = mapper.readTree(json);
                        return node.get(name).asText();
                    }
                }
                """);

        CheckResult r = new UpgradeChecker(resolver)
                .check(app, "com.fasterxml.jackson.core", "jackson-databind", "2.17.2");

        assertThat(r.breaks()).isEmpty();
        assertThat(sentences(r.warnings()))
                .contains("Json.java:12 reads PropertyNamingStrategy.SNAKE_CASE, which is now deprecated");
        assertThat(r.warnings()).noneMatch(w -> w.kind() == ChangeKind.NEW_CHECKED_EXCEPTION);
        assertThat(r.hasBreaks()).isFalse();
    }

    @Test
    void commonsIo24To216() throws Exception {
        Path app = project("commons-io", "commons-io", "2.4", "com.example.Files2", """
                package com.example;

                import java.io.File;
                import java.io.InputStream;
                import org.apache.commons.io.FileUtils;
                import org.apache.commons.io.IOUtils;

                public class Files2 {
                    public String read(File f) throws Exception {
                        return FileUtils.readFileToString(f);
                    }

                    public String read(InputStream in) throws Exception {
                        try {
                            return IOUtils.toString(in);
                        } finally {
                            IOUtils.closeQuietly(in);
                        }
                    }

                    public long size(File dir) {
                        return FileUtils.sizeOfDirectory(dir);
                    }
                }
                """);

        CheckResult r = new UpgradeChecker(resolver).check(app, "commons-io", "commons-io", "2.16.1");

        assertThat(r.breaks()).isEmpty();
        assertThat(sentences(r.warnings())).contains(
                "Files2.java:10 calls FileUtils.readFileToString(File), which is now deprecated",
                "Files2.java:15 calls IOUtils.toString(InputStream), which is now deprecated");
    }

    @Test
    void kotlinCompiledMultiReleaseJarsAreReadWithoutProblems() {
        ApiModel v19 = ApiReader.read(resolver.resolveJar(new Gav("org.jetbrains.kotlin", "kotlin-stdlib", "1.9.25")));
        ApiModel v20 = ApiReader.read(resolver.resolveJar(new Gav("org.jetbrains.kotlin", "kotlin-stdlib", "2.0.21")));

        assertThat(v19.classes()).containsKey("kotlin/collections/CollectionsKt");
        assertThat(v19.problems()).isEmpty();
        assertThat(v20.problems()).isEmpty();
        assertThat(ApiDiffer.diff(v19, v20).changes()).isNotNull();
    }
}
