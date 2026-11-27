package dev.tremor.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.tremor.analysis.UpdateRow;
import dev.tremor.cli.TremorVersion;
import java.io.PrintWriter;
import java.util.List;

/** The {@code --all-updates} table in text, JSON or HTML. */
public final class UpdatesReport {

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public void text(List<UpdateRow> rows, boolean color, PrintWriter out) {
        out.println("Tremor " + TremorVersion.current() + " · checking the newest version of every direct dependency");
        out.println();
        int depWidth = Math.max(10, rows.stream().mapToInt(r -> r.key().length()).max().orElse(0));
        int curWidth = Math.max(7, rows.stream().mapToInt(r -> r.current().length()).max().orElse(0));
        int newWidth = Math.max(6, rows.stream().mapToInt(r -> r.latest() == null ? 1 : r.latest().length())
                .max().orElse(0));
        String format = "%-" + depWidth + "s  %-" + curWidth + "s  %-" + newWidth + "s  %s%n";
        out.printf(format, "DEPENDENCY", "CURRENT", "LATEST", "VERDICT");
        for (UpdateRow r : rows) {
            out.printf(format, r.key(), r.current(), r.latest() == null ? "-" : r.latest(), verdict(r, color));
        }
        out.println();
        long breaks = rows.stream().filter(r -> r.verdict() == UpdateRow.Verdict.BREAKS).count();
        long risky = rows.stream().filter(r -> r.verdict() == UpdateRow.Verdict.RISKY).count();
        long safe = rows.stream().filter(r -> r.verdict() == UpdateRow.Verdict.SAFE).count();
        out.println(safe + " safe, " + risky + " risky, " + breaks + " with breaks. Run 'tremor check --dependency"
                + " <group:artifact> --to <version>' for the details of one upgrade.");
        out.flush();
    }

    private static String verdict(UpdateRow r, boolean color) {
        String text = switch (r.verdict()) {
            case SAFE -> "✅ safe";
            case RISKY -> "⚠️  risky (" + Summary.plural(r.result().warnings().size(), "warning") + ")";
            case BREAKS -> "❌ breaks (" + Summary.plural(r.result().breaks().size(), "break") + ")";
            case UP_TO_DATE -> "up to date";
            case ERROR -> "error: " + r.error();
        };
        if (!color) {
            return text;
        }
        String code = switch (r.verdict()) {
            case SAFE -> "32";
            case RISKY -> "33";
            case BREAKS, ERROR -> "31";
            case UP_TO_DATE -> "2";
        };
        return "\u001b[" + code + "m" + text + "\u001b[0m";
    }

    public void json(List<UpdateRow> rows, PrintWriter out) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schemaVersion", JsonReport.SCHEMA_VERSION);
        root.put("mode", "all-updates");
        ArrayNode updates = root.putArray("updates");
        for (UpdateRow r : rows) {
            ObjectNode n = updates.addObject();
            n.put("groupId", r.groupId());
            n.put("artifactId", r.artifactId());
            n.put("current", r.current());
            n.put("latest", r.latest());
            n.put("verdict", r.verdict().name().toLowerCase().replace('_', '-'));
            n.put("breaks", r.result() == null ? 0 : r.result().breaks().size());
            n.put("warnings", r.result() == null ? 0 : r.result().warnings().size());
            n.put("error", r.error());
            if (r.result() != null) {
                n.set("report", new JsonReport().toJson(r.result()));
            }
        }
        try {
            out.println(MAPPER.writeValueAsString(root));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        out.flush();
    }

    public void html(List<UpdateRow> rows, PrintWriter out) {
        StringBuilder h = new StringBuilder("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>Tremor: all updates"
                + "</title><style>body{margin:0;background:#0f1115;color:#e6e8ee;font:15px/1.55 -apple-system,"
                + "BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif}main{max-width:980px;margin:0 auto;"
                + "padding:32px 16px}table{width:100%;border-collapse:collapse;background:#171a21;border-radius:10px;"
                + "overflow:hidden}th,td{text-align:left;padding:10px 12px;border-bottom:1px solid #262b36;"
                + "word-break:break-word}th{color:#9aa3b2;font-weight:600;font-size:13px}code{font-family:ui-monospace,"
                + "Menlo,Consolas,monospace;font-size:13px}.SAFE{color:#5fd38d}.RISKY{color:#f5c451}"
                + ".BREAKS,.ERROR{color:#ff6b6b}.UP_TO_DATE{color:#9aa3b2}ul{margin:4px 0 0;padding-left:18px;"
                + "color:#9aa3b2;font-size:13px}</style></head><body><main><h1>All dependency updates</h1>"
                + "<table><tr><th>Dependency</th><th>Current</th><th>Latest</th><th>Verdict</th></tr>");
        for (UpdateRow r : rows) {
            h.append("<tr><td><code>").append(HtmlReport.esc(r.key())).append("</code>");
            if (r.result() != null && !r.result().breaks().isEmpty()) {
                h.append("<ul>");
                r.result().breaks().stream().limit(5).forEach(b -> h.append("<li>")
                        .append(HtmlReport.esc(SourcePaths.fileAndLine(r.result().projectDir(), b) + " "
                                + b.action() + ", which " + b.reason())).append("</li>"));
                h.append("</ul>");
            }
            h.append("</td><td>").append(HtmlReport.esc(r.current())).append("</td><td>")
                    .append(HtmlReport.esc(r.latest() == null ? "-" : r.latest())).append("</td><td class=\"")
                    .append(r.verdict().name()).append("\">").append(HtmlReport.esc(verdict(r, false)))
                    .append("</td></tr>");
        }
        h.append("</table></main></body></html>\n");
        out.print(h);
        out.flush();
    }
}
