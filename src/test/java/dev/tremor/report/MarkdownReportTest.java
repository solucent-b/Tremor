package dev.tremor.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MarkdownReportTest {

    private static String render(Path dir, List<dev.tremor.analysis.Finding> findings) {
        StringWriter s = new StringWriter();
        new MarkdownReport().write(TextReportTest.result(dir, findings, List.of()), new PrintWriter(s));
        return s.toString();
    }

    @Test
    void safe(@TempDir Path dir) {
        String md = render(dir, List.of());

        assertThat(md).startsWith(MarkdownReport.MARKER)
                .contains("✅ **Tremor:** upgrading `guava` 20.0 → 33.0.0-jre is safe for this project.")
                .contains("1 other library scanned");
    }

    @Test
    void breaksAreListed(@TempDir Path dir) {
        String md = render(dir, List.of(TextReportTest.projectBreak(), TextReportTest.libraryBreak()));

        assertThat(md)
                .contains("⚠️ **Tremor:** 2 things will break when upgrading `guava` 20.0 → 33.0.0-jre.")
                .contains("- `com/example/UserService.java:84` calls `Cache.getIfPresent(Object)`, which was removed"
                        + " — Maybe use Cache.getIfPresentOrNull(Object) instead.")
                .contains("- **library-y 1.4.2** `y/Validator.java:4` calls `Strings.isNullOrEmpty(String)`");
    }

    @Test
    void warningsOnly(@TempDir Path dir) {
        String md = render(dir, List.of(TextReportTest.warning()));

        assertThat(md).contains("has no known breaks for this project, but 1 thing deserves a look:")
                .contains("calls `Files.toString(File, Charset)`, which is now deprecated");
    }
}
