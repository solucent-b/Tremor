package dev.tremor.diff;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tremor.bytecode.ApiReader;
import dev.tremor.testing.JavaCompilation;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApiDifferTest {

    @TempDir
    Path temp;

    private ApiDiff diff(String v1, String v2) {
        return diff(Map.of("lib.A", v1), Map.of("lib.A", v2));
    }

    private ApiDiff diff(Map<String, String> v1, Map<String, String> v2) {
        Path old = JavaCompilation.compile(temp.resolve("v1"), List.of(), v1);
        Path neu = JavaCompilation.compile(temp.resolve("v2"), List.of(), v2);
        return ApiDiffer.diff(ApiReader.read(old), ApiReader.read(neu));
    }

    private static List<ChangeKind> kinds(ApiDiff diff) {
        return diff.ownChanges().stream().map(ApiChange::kind).toList();
    }

    @Test
    void identicalApisHaveNoChanges() {
        String src = "package lib; public class A { public void a() {} }";
        assertThat(diff(src, src).changes()).isEmpty();
    }

    @Test
    void privateChangesAreIgnored() {
        assertThat(diff(
                "package lib; public class A { private void a() {} void b() {} }",
                "package lib; public class A { }").changes()).isEmpty();
    }

    @Test
    void removedMethodWithSimilarNameHint() {
        ApiDiff d = diff(
                "package lib; public class A { public Object getIfPresent(Object k) { return null; } }",
                "package lib; public class A { public Object getIfPresentOrNull(Object k) { return null; } }");

        ApiChange c = d.forMethod("lib/A", "getIfPresent", "(Ljava/lang/Object;)Ljava/lang/Object;").get(0);
        assertThat(c.kind()).isEqualTo(ChangeKind.REMOVED_METHOD);
        assertThat(c.description()).isEqualTo("A.getIfPresent(Object) was removed");
        assertThat(c.hint()).contains("A.getIfPresentOrNull(Object)");
    }

    @Test
    void changedParametersSuggestTheNewOverload() {
        ApiDiff d = diff(
                "package lib; public class A { public void put(String k) {} }",
                "package lib; public class A { public void put(String k, int ttl) {} }");

        ApiChange c = d.forMethod("lib/A", "put", "(Ljava/lang/String;)V").get(0);
        assertThat(c.kind()).isEqualTo(ChangeKind.PARAMETERS_CHANGED);
        assertThat(c.hint()).isEqualTo("Use A.put(String, int) instead.");
    }

    @Test
    void changedReturnType() {
        ApiDiff d = diff(
                "package lib; public class A { public int size() { return 0; } }",
                "package lib; public class A { public long size() { return 0; } }");

        assertThat(kinds(d)).containsExactly(ChangeKind.RETURN_TYPE_CHANGED);
        assertThat(d.ownChanges().get(0).description()).contains("now returns long instead of int");
    }

    @Test
    void methodMovedToSuperclassIsNotABreak() {
        ApiDiff d = diff(
                Map.of("lib.Base", "package lib; public class Base {}",
                        "lib.A", "package lib; public class A extends Base { public void m() {} }"),
                Map.of("lib.Base", "package lib; public class Base { public void m() {} }",
                        "lib.A", "package lib; public class A extends Base { }"));

        assertThat(d.changes()).isEmpty();
    }

    @Test
    void inheritedRemovalIsReportedOnSubclassesToo() {
        ApiDiff d = diff(
                Map.of("lib.Base", "package lib; public class Base { public void m() {} }",
                        "lib.A", "package lib; public class A extends Base { }"),
                Map.of("lib.Base", "package lib; public class Base { }",
                        "lib.A", "package lib; public class A extends Base { }"));

        assertThat(d.forMethod("lib/A", "m", "()V")).singleElement()
                .satisfies(c -> assertThat(c.inherited()).isTrue());
        assertThat(d.ownChanges()).singleElement()
                .satisfies(c -> assertThat(c.className()).isEqualTo("lib/Base"));
    }

    @Test
    void visibilityReduced() {
        ApiDiff d = diff(
                "package lib; public class A { public void m() {} public int f; }",
                "package lib; public class A { protected void m() {} int f; }");

        assertThat(kinds(d)).containsExactlyInAnyOrder(ChangeKind.METHOD_LESS_VISIBLE, ChangeKind.FIELD_LESS_VISIBLE);
    }

    @Test
    void classBecameFinalAndAbstract() {
        assertThat(kinds(diff("package lib; public class A {}", "package lib; public final class A {}")))
                .containsExactly(ChangeKind.CLASS_NOW_FINAL);
        assertThat(kinds(diff("package lib; public class A {}", "package lib; public abstract class A {}")))
                .containsExactly(ChangeKind.CLASS_NOW_ABSTRACT);
    }

    @Test
    void methodBecameFinalOrAbstract() {
        assertThat(kinds(diff(
                "package lib; public class A { public void m() {} }",
                "package lib; public class A { public final void m() {} }")))
                .containsExactly(ChangeKind.METHOD_NOW_FINAL);
        assertThat(kinds(diff(
                "package lib; public abstract class A { public void m() {} }",
                "package lib; public abstract class A { public abstract void m(); }")))
                .containsExactly(ChangeKind.METHOD_NOW_ABSTRACT);
    }

    @Test
    void removedClassSuggestsWhereItMoved() {
        ApiDiff d = diff(
                Map.of("lib.Util", "package lib; public class Util {}", "lib.A", "package lib; public class A {}"),
                Map.of("lib.util.Util", "package lib.util; public class Util {}",
                        "lib.A", "package lib; public class A {}"));

        ApiChange c = d.forClass("lib/Util").get(0);
        assertThat(c.kind()).isEqualTo(ChangeKind.REMOVED_CLASS);
        assertThat(c.hint()).contains("lib.util.Util");
    }

    @Test
    void classNoLongerPublic() {
        assertThat(kinds(diff("package lib; public class A {}", "package lib; class A {}")))
                .containsExactly(ChangeKind.CLASS_LESS_VISIBLE);
    }

    @Test
    void removedInterface() {
        ApiDiff d = diff(
                "package lib; public class A implements java.io.Closeable { public void close() {} }",
                "package lib; public class A { public void close() {} }");

        assertThat(kinds(d)).containsExactly(ChangeKind.REMOVED_SUPERTYPE);
        assertThat(d.ownChanges().get(0).description()).contains("Closeable");
    }

    @Test
    void classToInterface() {
        assertThat(kinds(diff("package lib; public class A {}", "package lib; public interface A {}")))
                .containsExactly(ChangeKind.CLASS_KIND_CHANGED);
    }

    @Test
    void staticToInstance() {
        ApiDiff d = diff(
                "package lib; public class A { public static void m() {} public static int f; }",
                "package lib; public class A { public void m() {} public int f; }");

        assertThat(kinds(d)).containsExactlyInAnyOrder(ChangeKind.METHOD_STATIC_CHANGED, ChangeKind.FIELD_STATIC_CHANGED);
    }

    @Test
    void newCheckedExceptionIsAWarningButUncheckedIsIgnored() {
        ApiDiff d = diff(
                "package lib; public class A { public void m() {} public void n() {} }",
                "package lib; public class A { public void m() throws java.io.IOException {}"
                        + " public void n() throws IllegalStateException {} }");

        assertThat(d.ownChanges()).singleElement().satisfies(c -> {
            assertThat(c.kind()).isEqualTo(ChangeKind.NEW_CHECKED_EXCEPTION);
            assertThat(c.severity()).isEqualTo(Severity.WARNING);
            assertThat(c.memberName()).isEqualTo("m");
        });
    }

    @Test
    void narrowerExceptionIsNotANewCheckedException() {
        ApiDiff d = diff(
                "package lib; public class A { public void m() throws java.io.IOException {} }",
                "package lib; public class A { public void m() throws java.io.FileNotFoundException {} }");

        assertThat(d.changes()).isEmpty();
    }

    @Test
    void exceptionFromAnotherJarOnlyWarnsWhenNothingWasDeclaredBefore() {
        Path ext = JavaCompilation.compile(temp.resolve("ext"), List.of(), Map.of(
                "ext.ParseException", "package ext; public class ParseException extends java.io.IOException {}"));
        Path old = JavaCompilation.compile(temp.resolve("v1"), List.of(ext), Map.of("lib.A",
                "package lib; public class A { public void a() throws java.io.IOException {} public void b() {} }"));
        Path neu = JavaCompilation.compile(temp.resolve("v2"), List.of(ext), Map.of("lib.A",
                "package lib; public class A { public void a() throws ext.ParseException {}"
                        + " public void b() throws ext.ParseException {} }"));

        ApiDiff d = ApiDiffer.diff(ApiReader.read(old), ApiReader.read(neu));

        assertThat(d.ownChanges()).singleElement().satisfies(c -> {
            assertThat(c.kind()).isEqualTo(ChangeKind.NEW_CHECKED_EXCEPTION);
            assertThat(c.memberName()).isEqualTo("b");
        });
    }

    @Test
    void newlyDeprecated() {
        ApiDiff d = diff(
                "package lib; public class A { public void m() {} }",
                "package lib; public class A { @Deprecated public void m() {} }");

        assertThat(kinds(d)).containsExactly(ChangeKind.DEPRECATED);
    }

    @Test
    void abstractMethodAddedToInterface() {
        ApiDiff d = diff(
                "package lib; public interface A { void a(); }",
                "package lib; public interface A { void a(); void b(); default void c() {} }");

        assertThat(kinds(d)).containsExactly(ChangeKind.ABSTRACT_METHOD_ADDED);
        assertThat(d.ownChanges().get(0).memberName()).isEqualTo("b");
    }

    @Test
    void fieldChanges() {
        ApiDiff d = diff(
                "package lib; public class A { public int a; public int b; public String c; }",
                "package lib; public class A { public long a; public final int b = 1; }");

        assertThat(kinds(d)).containsExactlyInAnyOrder(
                ChangeKind.FIELD_TYPE_CHANGED, ChangeKind.FIELD_NOW_FINAL, ChangeKind.REMOVED_FIELD);
    }

    @Test
    void removedConstructor() {
        ApiDiff d = diff(
                "package lib; public class A { public A() {} public A(int x) {} }",
                "package lib; public class A { public A() {} }");

        assertThat(d.ownChanges()).singleElement().satisfies(c -> {
            assertThat(c.kind()).isEqualTo(ChangeKind.PARAMETERS_CHANGED);
            assertThat(c.description()).startsWith("new A(int)");
        });
    }

    @Test
    void removedStaticMethodThatMovedToAnotherClass() {
        ApiDiff d = diff(
                Map.of("lib.Objects", "package lib; public class Objects {"
                        + " public static String toStringHelper(Object o) { return \"\"; } }"),
                Map.of("lib.Objects", "package lib; public class Objects { }",
                        "lib.MoreObjects", "package lib; public class MoreObjects {"
                                + " public static StringBuilder toStringHelper(Object o) { return null; } }"));

        ApiChange c = d.forMethod("lib/Objects", "toStringHelper", "(Ljava/lang/Object;)Ljava/lang/String;").get(0);
        assertThat(c.hint()).isEqualTo("It may have moved: use MoreObjects.toStringHelper(Object) instead.");
    }

    @Test
    void movedNestedClassIsSuggested() {
        ApiDiff d = diff(
                Map.of("lib.Objects", "package lib; public class Objects { public static class Helper {} }"),
                Map.of("lib.Objects", "package lib; public class Objects { }",
                        "lib.MoreObjects", "package lib; public class MoreObjects { public static class Helper {} }"));

        assertThat(d.forClass("lib/Objects$Helper").get(0).hint()).isEqualTo("It may have moved: try lib.MoreObjects.Helper.");
    }

    @Test
    void jdkMethodsAreNeverSuggested() {
        ApiDiff d = diff(
                "package lib; public class A { public String toStringHelper() { return null; } }",
                "package lib; public class A { }");

        assertThat(d.forMethod("lib/A", "toStringHelper", "()Ljava/lang/String;").get(0).hint()).isNull();
    }

    @Test
    void similarNames() {
        assertThat(ApiDiffer.similarName("isEmpty", "isEmptyString")).isTrue();
        assertThat(ApiDiffer.similarName("getValue", "getValues")).isTrue();
        assertThat(ApiDiffer.similarName("put", "remove")).isFalse();
    }
}
