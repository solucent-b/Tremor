package dev.tremor.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HtmlReportTest {

    private static String render(Path dir, List<dev.tremor.analysis.Finding> findings) {
        StringWriter s = new StringWriter();
        new HtmlReport().write(TextReportTest.result(dir, findings, List.of()), new PrintWriter(s));
        return s.toString();
    }

    @Test
    void selfContainedPageWithSummaryAndCollapsibleSections(@TempDir Path dir) {
        String html = render(dir, List.of(TextReportTest.projectBreak(), TextReportTest.libraryBreak(),
                TextReportTest.warning()));

        assertThat(html).startsWith("<!doctype html>")
                .contains("<style>")
                .doesNotContain("<link", "<script", "http://", "https://")
                .contains("❌ 2 breaks, 1 warning across 2 files and 1 library")
                .contains("<details open><summary>❌ Breaks (2)</summary>")
                .contains("<details><summary>⚠️ Warnings (1)</summary>")
                .contains("com/example/UserService.java:84")
                .contains("calls Cache.getIfPresent(Object), which was removed")
                .contains("library-y 1.4.2 (library)")
                .endsWith("</html>\n");
    }

    @Test
    void safePage(@TempDir Path dir) {
        assertThat(render(dir, List.of())).contains("verdict safe").contains("✅ Safe: nothing you use changed.");
    }

    @Test
    void escapesHtml() {
        assertThat(HtmlReport.esc("List<String> & \"x\"")).isEqualTo("List&lt;String&gt; &amp; &quot;x&quot;");
    }
}
