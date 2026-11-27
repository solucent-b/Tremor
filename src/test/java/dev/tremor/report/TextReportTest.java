package dev.tremor.report;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tremor.analysis.CheckResult;
import dev.tremor.analysis.Finding;
import dev.tremor.analysis.MediationIssue;
import dev.tremor.analysis.Origin;
import dev.tremor.diff.ChangeKind;
import dev.tremor.diff.Severity;
import dev.tremor.project.Gav;
import dev.tremor.scan.Location;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TextReportTest {

    static final Gav FROM = new Gav("com.google.guava", "guava", "20.0");
    static final Gav TO = new Gav("com.google.guava", "guava", "33.0.0-jre");
    static final Gav APP = new Gav("com.example", "app", "1.0");
    static final Gav LIB_Y = new Gav("org.y", "library-y", "1.4.2");

    static Finding projectBreak() {
        return new Finding(Severity.BREAKING, ChangeKind.REMOVED_METHOD, Origin.PROJECT,
                new Location("com/example/UserService", "UserService.java", 84, "load"),
                "calls Cache.getIfPresent(Object)", "was removed", "NoSuchMethodError at runtime",
                "Maybe use Cache.getIfPresentOrNull(Object) instead.");
    }

    static Finding libraryBreak() {
        return new Finding(Severity.BREAKING, ChangeKind.REMOVED_METHOD, Origin.library(LIB_Y),
                new Location("y/Validator", "Validator.java", 4, "valid"),
                "calls Strings.isNullOrEmpty(String)", "was removed", "NoSuchMethodError at runtime", null);
    }

    static Finding warning() {
        return new Finding(Severity.WARNING, ChangeKind.DEPRECATED, Origin.PROJECT,
                new Location("com/example/Other", "Other.java", 12, "run"),
                "calls Files.toString(File, Charset)", "is now deprecated", null, null);
    }

    static CheckResult result(Path dir, List<Finding> findings, List<MediationIssue> mediation) {
        return new CheckResult(dir, APP, FROM, TO, findings, mediation,
                new CheckResult.ApiChangeCounts(12, 3), List.of(LIB_Y), List.of("Some note."));
    }

    private static String render(CheckResult r) {
        StringWriter s = new StringWriter();
        new TextReport(false).write(r, new PrintWriter(s));
        return s.toString();
    }

    @Test
    void groupsBreaksAndWarningsWithFileLineAndHints(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("src/main/java/com/example/UserService.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "class UserService {}");

        String text = render(result(dir, List.of(projectBreak(), libraryBreak(), warning()), List.of()));

        assertThat(text)
                .contains("guava 20.0 → 33.0.0-jre")
                .contains("❌ Breaks (2)")
                .contains("Your project")
                .contains("src/main/java/com/example/UserService.java:84")
                .contains("calls Cache.getIfPresent(Object), which was removed")
                .contains("→ NoSuchMethodError at runtime")
                .contains("💡 Maybe use Cache.getIfPresentOrNull(Object) instead.")
                .contains("library-y 1.4.2 (library)")
                .contains("y/Validator.java:4")
                .contains("⚠️  Warnings (1)")
                .contains("• Some note.")
                .endsWith("❌ 2 breaks, 1 warning across 2 files and 1 library" + System.lineSeparator());
        assertThat(text.indexOf("Your project")).isLessThan(text.indexOf("library-y 1.4.2 (library)"));
    }

    @Test
    void safeReport(@TempDir Path dir) {
        String text = render(result(dir, List.of(), List.of()));

        assertThat(text).contains("✅ Safe: nothing you use changed.")
                .contains("12 breaking changes, but none of them touch code Tremor can see")
                .contains("✅ 0 breaks, 0 warnings — safe to upgrade");
    }

    @Test
    void showsRiskyVersionChangesAndCountsLowOnes(@TempDir Path dir) {
        var risky = new MediationIssue(LIB_Y, "com.google.guava:guava", "34.0", "33.0.0-jre",
                MediationIssue.Risk.RISKY, "library-y 1.4.2 asks for guava 34.0 but will get 33.0.0-jre, which is OLDER.");
        var low = new MediationIssue(new Gav("o", "z", "1"), "com.google.guava:guava", "32.0", "33.0.0-jre",
                MediationIssue.Risk.LOW, "z 1 asks for guava 32.0 but will get 33.0.0-jre.");

        String text = render(result(dir, List.of(), List.of(risky, low)));

        assertThat(text).contains("🔀 Version changes")
                .contains("⚠️  library-y 1.4.2 asks for guava 34.0")
                .contains("1 low-risk version change")
                .contains("⚠️  0 breaks, 0 warnings across 1 library");
    }

    @Test
    void colorsOnlyWhenAsked(@TempDir Path dir) {
        StringWriter s = new StringWriter();
        new TextReport(true).write(result(dir, List.of(projectBreak()), List.of()), new PrintWriter(s));

        assertThat(s.toString()).contains("\u001b[31m");
        assertThat(render(result(dir, List.of(projectBreak()), List.of()))).doesNotContain("\u001b[");
    }
}
