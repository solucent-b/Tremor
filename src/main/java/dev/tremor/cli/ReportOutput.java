package dev.tremor.cli;

import dev.tremor.analysis.CheckResult;
import dev.tremor.report.JsonReport;
import dev.tremor.report.TextReport;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    private static void render(ReportFormat format, boolean color, CheckResult result, PrintWriter out) {
        switch (format) {
            case TEXT -> new TextReport(color).write(result, out);
            case JSON -> new JsonReport().write(result, out);
            default -> throw new IllegalArgumentException("--format " + format.name().toLowerCase()
                    + " is not supported yet; use text");
        }
    }

    private static boolean useColor(boolean noColor) {
        return !noColor && System.console() != null && System.getenv("NO_COLOR") == null
                && !"dumb".equals(System.getenv("TERM"));
    }
}
