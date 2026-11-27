package dev.tremor.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tremor.bytecode.ApiModel;
import dev.tremor.bytecode.ApiReader;
import dev.tremor.diff.ApiDiffer;
import dev.tremor.diff.ChangeKind;
import dev.tremor.diff.Severity;
import dev.tremor.scan.UsageScanner;
import dev.tremor.testing.JavaCompilation;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UsageMatcherTest {

    @TempDir
    Path temp;

    /** Compiles the app against v1, then checks it against v2. */
    private List<Finding> check(String v1, String v2, String app) {
        Path old = JavaCompilation.compile(temp.resolve("v1"), List.of(), Map.of("lib.Lib", v1));
        Path neu = JavaCompilation.compile(temp.resolve("v2"), List.of(), Map.of("lib.Lib", v2));
        Path appClasses = JavaCompilation.compile(temp.resolve("app"), List.of(old), Map.of("app.App", app));
        ApiModel o = ApiReader.read(old);
        ApiModel n = ApiReader.read(neu);
        var scan = new UsageScanner(o::contains).scan(appClasses);
        return new UsageMatcher(o, n, ApiDiffer.diff(o, n)).match(scan, Origin.PROJECT);
    }

    @Test
    void unchangedUsageIsSafe() {
        String lib = "package lib; public class Lib { public static String hi() { return \"\"; } }";
        assertThat(check(lib, lib, "package app; class App { void a() { lib.Lib.hi(); } }")).isEmpty();
    }

    @Test
    void unusedChangesAreSafe() {
        assertThat(check(
                "package lib; public class Lib { public static void used() {} public static void unused() {} }",
                "package lib; public class Lib { public static void used() {} }",
                "package app; class App { void a() { lib.Lib.used(); } }")).isEmpty();
    }

    @Test
    void removedMethodIsABreakWithFileAndLine() {
        List<Finding> f = check(
                "package lib; public class Lib { public Object getIfPresent(Object k) { return null; } }",
                "package lib; public class Lib { public Object getIfPresentOrNull(Object k) { return null; } }",
                """
                package app;
                class App {
                    Object a(lib.Lib c) {
                        return c.getIfPresent("k");
                    }
                }
                """);

        assertThat(f).singleElement().satisfies(b -> {
            assertThat(b.severity()).isEqualTo(Severity.BREAKING);
            assertThat(b.sentence()).isEqualTo("App.java:4 calls Lib.getIfPresent(Object), which was removed");
            assertThat(b.consequence()).isEqualTo("NoSuchMethodError at runtime");
            assertThat(b.hint()).contains("Lib.getIfPresentOrNull(Object)");
        });
    }

    @Test
    void changedParameters() {
        List<Finding> f = check(
                "package lib; public class Lib { public static void put(String k) {} }",
                "package lib; public class Lib { public static void put(String k, int ttl) {} }",
                "package app; class App { void a() { lib.Lib.put(\"k\"); } }");

        assertThat(f).singleElement().satisfies(b -> {
            assertThat(b.kind()).isEqualTo(ChangeKind.PARAMETERS_CHANGED);
            assertThat(b.hint()).isEqualTo("Use Lib.put(String, int) instead.");
        });
    }

    @Test
    void methodMovedToSuperclassIsSafe() {
        Path old = JavaCompilation.compile(temp.resolve("v1"), List.of(), Map.of(
                "lib.Base", "package lib; public class Base {}",
                "lib.Lib", "package lib; public class Lib extends Base { public void m() {} }"));
        Path neu = JavaCompilation.compile(temp.resolve("v2"), List.of(), Map.of(
                "lib.Base", "package lib; public class Base { public void m() {} }",
                "lib.Lib", "package lib; public class Lib extends Base {}"));
        Path app = JavaCompilation.compile(temp.resolve("app"), List.of(old), Map.of(
                "app.App", "package app; class App { void a(lib.Lib l) { l.m(); } }"));
        ApiModel o = ApiReader.read(old);
        ApiModel n = ApiReader.read(neu);

        var findings = new UsageMatcher(o, n, ApiDiffer.diff(o, n))
                .match(new UsageScanner(o::contains).scan(app), Origin.PROJECT);

        assertThat(findings).isEmpty();
    }

    @Test
    void staticToInstanceBreaks() {
        List<Finding> f = check(
                "package lib; public class Lib { public static void m() {} }",
                "package lib; public class Lib { public void m() {} }",
                "package app; class App { void a() { lib.Lib.m(); } }");

        assertThat(f).singleElement().satisfies(b -> {
            assertThat(b.kind()).isEqualTo(ChangeKind.METHOD_STATIC_CHANGED);
            assertThat(b.consequence()).contains("IncompatibleClassChangeError");
        });
    }

    @Test
    void extendingAClassThatBecameFinalBreaks() {
        List<Finding> f = check(
                "package lib; public class Lib {}",
                "package lib; public final class Lib {}",
                "package app; class App extends lib.Lib {}");

        assertThat(f).extracting(Finding::kind).contains(ChangeKind.CLASS_NOW_FINAL);
    }

    @Test
    void overridingAMethodThatBecameFinalBreaks() {
        List<Finding> f = check(
                "package lib; public class Lib { public void hook() {} }",
                "package lib; public class Lib { public final void hook() {} }",
                "package app; class App extends lib.Lib { public void hook() {} }");

        assertThat(f).singleElement().satisfies(b -> {
            assertThat(b.kind()).isEqualTo(ChangeKind.METHOD_NOW_FINAL);
            assertThat(b.action()).isEqualTo("overrides Lib.hook()");
        });
    }

    @Test
    void implementingAnInterfaceThatGainedAnAbstractMethodBreaks() {
        List<Finding> f = check(
                "package lib; public interface Lib { void a(); }",
                "package lib; public interface Lib { void a(); void b(); }",
                "package app; class App implements lib.Lib { public void a() {} }");

        assertThat(f).singleElement().satisfies(b -> {
            assertThat(b.kind()).isEqualTo(ChangeKind.ABSTRACT_METHOD_ADDED);
            assertThat(b.hint()).isEqualTo("Implement b() in App.");
        });
    }

    @Test
    void removedClassBreaksEveryUse() {
        List<Finding> f = check(
                "package lib; public class Lib { public static void m() {} }",
                "package lib; class Lib { public static void m() {} }",
                "package app; class App { void a() { lib.Lib.m(); } }");

        assertThat(f).isNotEmpty().allSatisfy(b -> assertThat(b.isBreak()).isTrue());
        assertThat(f.get(0).reason()).isEqualTo("is no longer public");
    }

    @Test
    void writingAFieldThatBecameFinalBreaksButReadingIsFine() {
        List<Finding> f = check(
                "package lib; public class Lib { public static int LIMIT = 1; }",
                "package lib; public class Lib { public static final int LIMIT = Integer.getInteger(\"x\", 1); }",
                "package app; class App { int a() { lib.Lib.LIMIT = 2; return lib.Lib.LIMIT; } }");

        assertThat(f).singleElement().satisfies(b -> {
            assertThat(b.kind()).isEqualTo(ChangeKind.FIELD_NOW_FINAL);
            assertThat(b.action()).isEqualTo("writes Lib.LIMIT");
        });
    }

    @Test
    void deprecationIsAWarning() {
        List<Finding> f = check(
                "package lib; public class Lib { public static void m() {} }",
                "package lib; public class Lib { @Deprecated public static void m() {} }",
                "package app; class App { void a() { lib.Lib.m(); } }");

        assertThat(f).singleElement().satisfies(w -> {
            assertThat(w.severity()).isEqualTo(Severity.WARNING);
            assertThat(w.reason()).isEqualTo("is now deprecated");
        });
    }

    @Test
    void inheritedCallThroughLocalSubclassIsChecked() {
        List<Finding> f = check(
                "package lib; public class Lib { public void helper() {} }",
                "package lib; public class Lib { }",
                "package app; class App extends lib.Lib { void a() { helper(); } }");

        assertThat(f).singleElement().satisfies(b -> {
            assertThat(b.kind()).isEqualTo(ChangeKind.REMOVED_METHOD);
            assertThat(b.action()).isEqualTo("calls Lib.helper()");
        });
    }

    @Test
    void methodReferenceInLambdaIsChecked() {
        List<Finding> f = check(
                "package lib; public class Lib { public static String up(String s) { return s; } }",
                "package lib; public class Lib { }",
                "package app; class App { java.util.function.UnaryOperator<String> f = lib.Lib::up; }");

        assertThat(f).singleElement().satisfies(b -> assertThat(b.action()).startsWith("uses method reference"));
    }

    @Test
    void codeThatBundlesItsOwnCopyIsNotFlagged() {
        Path old = JavaCompilation.compile(temp.resolve("v1"), List.of(), Map.of(
                "lib.Lib", "package lib; public class Lib { public static void m() {} }"));
        Path neu = JavaCompilation.compile(temp.resolve("v2"), List.of(), Map.of(
                "lib.Lib", "package lib; public class Lib { }"));
        // A "fat" jar that contains its own copy of lib.Lib plus code calling it.
        Path fat = JavaCompilation.compile(temp.resolve("fat"), List.of(), Map.of(
                "lib.Lib", "package lib; public class Lib { public static void m() {} }",
                "app.App", "package app; class App { void a() { lib.Lib.m(); } }"));
        ApiModel o = ApiReader.read(old);
        ApiModel n = ApiReader.read(neu);

        var findings = new UsageMatcher(o, n, ApiDiffer.diff(o, n))
                .match(new UsageScanner(o::contains).scan(fat), Origin.PROJECT);

        assertThat(findings).isEmpty();
    }
}
