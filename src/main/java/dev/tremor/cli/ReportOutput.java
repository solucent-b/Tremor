package dev.tremor.cli;

import dev.tremor.analysis.CheckResult;
import dev.tremor.analysis.UpdateRow;
import dev.tremor.report.HtmlReport;
import dev.tremor.report.JsonReport;
import dev.tremor.report.MarkdownReport;
import dev.tremor.report.TextReport;
import dev.tremor.report.UpdatesReport;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import picocli.CommandLine.Model.CommandSpec;

/** Writes a report in the chosen format to the terminal or a file. */
final class ReportOutput {

    private ReportOutput() {
    }

    static void write(CommandSpec spec, Path output, ReportFormat format, boolean noColor, CheckResult result)
            throws IOException {
        if (output == null) {
            PrintWriter out = spec.commandLine().getOut();
            render(format, useColor(noColor), result, out);
            out.flush();
            return;
        }
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(output, StandardCharsets.UTF_8))) {
            render(format, false, result, out);
        }
        spec.commandLine().getErr().println("tremor: report written to " + output);
    }

    static void writeUpdates(CommandSpec spec, Path output, ReportFormat format, boolean noColor,
            List<UpdateRow> rows) throws IOException {
        UpdatesReport report = new UpdatesReport();
        if (output == null) {
            PrintWriter out = spec.commandLine().getOut();
            renderUpdates(report, format, useColor(noColor), rows, out);
            return;
        }
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(output, StandardCharsets.UTF_8))) {
            renderUpdates(report, format, false, rows, out);
        }
        spec.commandLine().getErr().println("tremor: report written to " + output);
    }

    private static void renderUpdates(UpdatesReport report, ReportFormat format, boolean color, List<UpdateRow> rows,
            PrintWriter out) {
        switch (format) {
            case TEXT -> report.text(rows, color, out);
            case JSON -> report.json(rows, out);
            case HTML -> report.html(rows, out);
            case MARKDOWN -> new MarkdownReport().updates(rows, out);
        }
    }

    private static void render(ReportFormat format, boolean color, CheckResult result, PrintWriter out) {
        switch (format) {
            case TEXT -> new TextReport(color).write(result, out);
            case JSON -> new JsonReport().write(result, out);
            case HTML -> new HtmlReport().write(result, out);
            case MARKDOWN -> new MarkdownReport().write(result, out);
        }
    }

    private static boolean useColor(boolean noColor) {
        return !noColor && System.console() != null && System.getenv("NO_COLOR") == null
                && !"dumb".equals(System.getenv("TERM"));
    }
}
