package dev.tremor.bytecode;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tremor.testing.JavaCompilation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApiReaderTest {

    @TempDir
    static Path temp;

    static ApiModel model;

    @BeforeAll
    static void compile() {
        Path jar = JavaCompilation.compileJar(temp, "lib", List.of(), Map.of(
                "lib.Base", """
                        package lib;
                        public abstract class Base implements java.io.Serializable {
                            public String inherited() { return ""; }
                            protected abstract void hook();
                            public static final int LIMIT = 3;
                        }
                        """,
                "lib.Thing", """
                        package lib;
                        public final class Thing extends Base implements Comparable<Thing> {
                            public Thing() {}
                            Thing(int hidden) {}
                            public int size;
                            private int secret;
                            @Deprecated public void old() {}
                            public static Thing of() { return new Thing(); }
                            protected void hook() {}
                            public int compareTo(Thing o) { return 0; }
                            void packagePrivate() {}
                            public static class Nested { public void n() {} }
                            private static class Hidden { public void h() {} }
                            public Runnable anon() { return new Runnable() { public void run() {} }; }
                        }
                        """,
                "lib.internal.Impl", """
                        package lib.internal;
                        class Impl { public void x() {} }
                        """));
        model = ApiReader.read(jar);
    }

    @Test
    void readsClassesWithHierarchy() {
        ClassInfo thing = model.get("lib/Thing");

        assertThat(thing.superName()).isEqualTo("lib/Base");
        assertThat(thing.interfaces()).containsExactly("java/lang/Comparable");
        assertThat(thing.isFinal()).isTrue();
        assertThat(model.supertypes("lib/Thing"))
                .contains("lib/Base", "java/lang/Object", "java/io/Serializable", "java/lang/Comparable");
    }

    @Test
    void readsMembersWithDescriptorsAndFlags() {
        ClassInfo thing = model.get("lib/Thing");

        assertThat(thing.methods()).containsKeys("<init>()V", "<init>(I)V", "of()Llib/Thing;", "old()V");
        assertThat(thing.methods().get("of()Llib/Thing;").isStatic()).isTrue();
        assertThat(thing.methods().get("old()V").deprecated()).isTrue();
        assertThat(thing.methods().get("<init>(I)V").isApi()).isFalse();
        assertThat(thing.fields().get("size").isApi()).isTrue();
        assertThat(thing.fields().get("secret").isApi()).isFalse();
    }

    @Test
    void onlyPublicAndProtectedClassesAreApi() {
        assertThat(model.isApiClass("lib/Thing")).isTrue();
        assertThat(model.isApiClass("lib/Thing$Nested")).isTrue();
        assertThat(model.isApiClass("lib/Thing$Hidden")).isFalse();
        assertThat(model.isApiClass("lib/Thing$1")).isFalse();
        assertThat(model.isApiClass("lib/internal/Impl")).isFalse();
    }

    @Test
    void resolvesInheritedMethodsAndFields() {
        Lookup<ResolvedMethod> inherited = model.resolveMethod("lib/Thing", "inherited", "()Ljava/lang/String;");
        assertThat(inherited.isFound()).isTrue();
        assertThat(inherited.value().declaringClass().name()).isEqualTo("lib/Base");

        assertThat(model.resolveMethod("lib/Thing", "hashCode", "()I").isFound()).isTrue();
        assertThat(model.resolveMethod("lib/Thing", "nope", "()V").isMissing()).isTrue();
        assertThat(model.resolveField("lib/Thing", "LIMIT").value().declaringClass().name()).isEqualTo("lib/Base");
    }

    @Test
    void constructorsAreNotInherited() {
        assertThat(model.resolveMethod("lib/Thing", "<init>", "()V").isFound()).isTrue();
        assertThat(model.visibleApiMethods("lib/Thing")).containsKey("<init>()V")
                .containsKey("inherited()Ljava/lang/String;")
                .doesNotContainKey("packagePrivate()V");
    }

    @Test
    void unknownSuperclassMakesLookupUnknownNotMissing(@TempDir Path dir) {
        Path base = JavaCompilation.compile(dir.resolve("base"), List.of(), Map.of(
                "other.Base", "package other; public class Base { public void fromOther() {} }"));
        Path jar = JavaCompilation.compileJar(dir, "child", List.of(base), Map.of(
                "child.Child", "package child; public class Child extends other.Base {}"));
        ApiModel childOnly = ApiReader.read(jar);

        Lookup<ResolvedMethod> r = childOnly.resolveMethod("child/Child", "fromOther", "()V");
        assertThat(r.isFound()).isFalse();
        assertThat(r.unknown()).isTrue();
    }

    @Test
    void skipsBrokenClassFilesAndKeepsGoing(@TempDir Path dir) throws Exception {
        Path classes = JavaCompilation.compile(dir.resolve("c"), List.of(), Map.of(
                "ok.Fine", "package ok; public class Fine {}"));
        Files.write(classes.resolve("ok/Broken.class"), new byte[] {(byte) 0xCA, (byte) 0xFE, 1, 2});
        Files.createDirectories(classes.resolve("META-INF/versions/11/ok"));
        Files.write(classes.resolve("META-INF/versions/11/ok/Fine.class"), new byte[] {1});

        ApiModel m = ApiReader.read(classes);

        assertThat(m.classes()).containsOnlyKeys("ok/Fine");
        assertThat(m.problems()).singleElement().asString().contains("ok/Broken.class");
    }
}
