package dev.tremor.scan;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tremor.testing.JavaCompilation;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UsageScannerTest {

    @TempDir
    static Path temp;

    static ScanResult result;

    @BeforeAll
    static void scan() {
        Path lib = JavaCompilation.compile(temp.resolve("lib"), List.of(), Map.of(
                "lib.Cache", """
                        package lib;
                        public class Cache {
                            public static int COUNT;
                            public String name;
                            public Cache() {}
                            public Object getIfPresent(Object key) { return null; }
                            public static Cache create() { return new Cache(); }
                            public void inherited() {}
                        }
                        """,
                "lib.Listener", "package lib; public interface Listener { void on(String e); }",
                "lib.Marker", "package lib; public @interface Marker {}",
                "lib.Oops", "package lib; public class Oops extends RuntimeException {}"));
        Path app = JavaCompilation.compile(temp.resolve("app"), List.of(lib), Map.of(
                "app.UserService", """
                        package app;
                        import lib.*;
                        import java.util.function.Function;
                        @Marker
                        public class UserService extends Cache implements Listener {
                            private Cache field;
                            public void on(String e) {}
                            public Object run(Object o) {
                                Cache c = Cache.create();
                                Object v = c.getIfPresent("k");
                                c.name = "x";
                                int n = Cache.COUNT;
                                Function<Object, Object> f = c::getIfPresent;
                                inherited();
                                if (o instanceof Cache) { return (Cache) o; }
                                try { new Cache(); } catch (Oops ex) { }
                                return Cache.class;
                            }
                        }
                        """));
        result = new UsageScanner(name -> name.startsWith("lib/")).scan(app);
    }

    private Usage find(UsageKind kind, String owner, String name) {
        return result.usages().stream()
                .filter(u -> u.kind() == kind && u.owner().equals(owner) && (name == null || name.equals(u.name())))
                .findFirst().orElseThrow(() -> new AssertionError("No " + kind + " of " + owner + "." + name
                        + " in " + result.usages()));
    }

    @Test
    void findsMethodCallsWithSourceAndLine() {
        Usage call = find(UsageKind.METHOD_CALL, "lib/Cache", "getIfPresent");

        assertThat(call.descriptor()).isEqualTo("(Ljava/lang/Object;)Ljava/lang/Object;");
        assertThat(call.location().sourcePath()).isEqualTo("app/UserService.java");
        assertThat(call.location().line()).isEqualTo(10);
        assertThat(call.location().member()).isEqualTo("run");
        assertThat(call.location().shortForm()).isEqualTo("UserService.java:10");
    }

    @Test
    void findsStaticCallsAndFields() {
        assertThat(find(UsageKind.METHOD_CALL, "lib/Cache", "create").isStaticAccess()).isTrue();
        assertThat(find(UsageKind.FIELD_WRITE, "lib/Cache", "name").location().line()).isEqualTo(11);
        assertThat(find(UsageKind.FIELD_READ, "lib/Cache", "COUNT").isStaticAccess()).isTrue();
    }

    @Test
    void findsConstructorsCastsAndInstanceof() {
        assertThat(find(UsageKind.NEW_INSTANCE, "lib/Cache", "<init>").location().line()).isEqualTo(16);
        find(UsageKind.CAST, "lib/Cache", null);
        find(UsageKind.INSTANCEOF, "lib/Cache", null);
        find(UsageKind.CLASS_LITERAL, "lib/Cache", null);
        find(UsageKind.CATCH, "lib/Oops", null);
    }

    @Test
    void findsInheritanceAndAnnotations() {
        find(UsageKind.EXTENDS, "lib/Cache", null);
        find(UsageKind.IMPLEMENTS, "lib/Listener", null);
        find(UsageKind.ANNOTATION, "lib/Marker", null);
        find(UsageKind.TYPE_REFERENCE, "lib/Cache", null);
    }

    @Test
    void superConstructorCallIsACallNotACreation() {
        Usage superCall = find(UsageKind.METHOD_CALL, "lib/Cache", "<init>");
        assertThat(superCall.location().member()).isEqualTo("<init>");
    }

    @Test
    void findsMethodReferencesInsideLambdas() {
        Usage ref = find(UsageKind.METHOD_REFERENCE, "lib/Cache", "getIfPresent");
        assertThat(ref.location().line()).isEqualTo(13);
    }

    @Test
    void keepsCallsOnLocalSubclassesThatMayLandInTheLibrary() {
        Usage inherited = find(UsageKind.METHOD_CALL, "app/UserService", "inherited");
        assertThat(inherited.location().line()).isEqualTo(14);
    }

    @Test
    void recordsScannedClassDeclarations() {
        ScannedClass c = result.classes().get("app/UserService");

        assertThat(c.superName()).isEqualTo("lib/Cache");
        assertThat(c.interfaces()).containsExactly("lib/Listener");
        assertThat(c.declaresMethod("on", "(Ljava/lang/String;)V")).isTrue();
        assertThat(c.sourceFile()).isEqualTo("UserService.java");
    }

    @Test
    void ignoresUninterestingOwners() {
        assertThat(result.usages()).noneMatch(u -> u.owner().startsWith("java/"));
        assertThat(result.usages()).allMatch(u -> u.owner().startsWith("lib/") || u.owner().startsWith("app/"));
    }
}
