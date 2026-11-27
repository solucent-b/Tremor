package dev.tremor.report;

import dev.tremor.analysis.CheckResult;
import dev.tremor.analysis.Finding;
import dev.tremor.analysis.MediationIssue;
import dev.tremor.cli.TremorVersion;
import java.io.PrintWriter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A single self-contained HTML page (no external CSS or JS) with a dark theme. */
public final class HtmlReport {

    private static final String CSS = """
            :root { --bg:#0f1115; --panel:#171a21; --line:#262b36; --text:#e6e8ee; --dim:#9aa3b2;
                    --red:#ff6b6b; --yellow:#f5c451; --green:#5fd38d; --blue:#7cc4ff; }
            * { box-sizing: border-box; }
            body { margin:0; background:var(--bg); color:var(--text);
                   font:15px/1.55 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
            main { max-width: 980px; margin: 0 auto; padding: 32px 16px 64px; }
            h1 { font-size: 22px; margin: 0 0 4px; }
            h1 .arrow { color: var(--dim); }
            .sub { color: var(--dim); margin-bottom: 24px; }
            .cards { display:grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap:12px;
                     margin-bottom: 24px; }
            .card { background:var(--panel); border:1px solid var(--line); border-radius:10px; padding:14px 16px; }
            .card .n { font-size: 28px; font-weight: 700; }
            .card .l { color: var(--dim); font-size: 13px; }
            .verdict { border-radius:10px; padding:14px 16px; margin-bottom:24px; font-weight:600;
                       border:1px solid var(--line); background:var(--panel); }
            .breaks .n, .verdict.breaks { color: var(--red); }
            .warnings .n, .verdict.warnings { color: var(--yellow); }
            .verdict.safe { color: var(--green); }
            details { background:var(--panel); border:1px solid var(--line); border-radius:10px;
                      margin-bottom:14px; }
            summary { cursor:pointer; padding:12px 16px; font-weight:600; list-style:none; }
            summary::-webkit-details-marker { display:none; }
            summary::before { content:"▸"; display:inline-block; width:1em; color:var(--dim); }
            details[open] > summary::before { content:"▾"; }
            .group { padding: 0 16px 8px; }
            .group h3 { font-size:14px; color:var(--dim); margin:12px 0 6px; font-weight:600; }
            .item { border-top:1px solid var(--line); padding:10px 0; }
            .loc { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; color: var(--blue);
                   font-size: 13px; word-break: break-all; }
            .what { margin-top:2px; }
            code { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 13px;
                   background:#0b0d11; padding:1px 5px; border-radius:4px; }
            .why { color: var(--dim); font-size: 13px; }
            .hint { font-size: 13px; margin-top: 2px; }
            .risk { font-size:12px; font-weight:700; padding:2px 7px; border-radius:999px; margin-right:6px; }
            .risk.BREAKS { background:#3a1717; color:var(--red); }
            .risk.RISKY { background:#3a3017; color:var(--yellow); }
            .risk.LOW { background:#1f2a22; color:var(--green); }
            ul.notes { color: var(--dim); font-size: 13px; padding: 0 16px 12px 34px; margin: 0; }
            footer { color: var(--dim); font-size: 12px; margin-top: 28px; }
            """;

    public void write(CheckResult r, PrintWriter out) {
        Summary s = Summary.of(r);
        StringBuilder h = new StringBuilder();
        h.append("<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .append("<title>Tremor: ").append(esc(r.from().artifactId())).append(' ')
                .append(esc(r.from().version())).append(" → ").append(esc(r.to().version())).append("</title>")
                .append("<style>").append(CSS).append("</style></head><body><main>\n");

        h.append("<h1>").append(esc(r.from().groupId() + ":" + r.from().artifactId())).append(' ')
                .append(esc(r.from().version())).append(" <span class=\"arrow\">→</span> ")
                .append(esc(r.to().version())).append("</h1>\n");
        h.append("<div class=\"sub\">Project <code>").append(esc(r.project().toString()))
                .append("</code> · ").append(r.scannedLibraries().size())
                .append(" other librar").append(r.scannedLibraries().size() == 1 ? "y" : "ies")
                .append(" scanned</div>\n");

        String verdictText = switch (s.verdict()) {
            case "breaks" -> "❌ " + s.line();
            case "warnings" -> "⚠️ " + s.line();
            default -> "✅ Safe: nothing you use changed. " + s.line();
        };
        h.append("<div class=\"verdict ").append(s.verdict()).append("\">").append(esc(verdictText)).append("</div>\n");

        h.append("<div class=\"cards\">");
        card(h, "breaks", s.breaks(), "breaks");
        card(h, "warnings", s.warnings(), "warnings");
        card(h, "", s.files(), "files affected");
        card(h, "", s.libraries(), "libraries affected");
        card(h, "", (int) r.apiChanges().breaking(), "breaking API changes in the library");
        h.append("</div>\n");

        section(h, r, "❌ Breaks", r.breaks(), true);
        section(h, r, "⚠️ Warnings", r.warnings(), r.breaks().isEmpty());
        mediation(h, r.mediation());

        if (!r.notes().isEmpty()) {
            h.append("<details><summary>Notes</summary><ul class=\"notes\">");
            r.notes().forEach(n -> h.append("<li>").append(esc(n)).append("</li>"));
            h.append("</ul></details>\n");
        }
        h.append("<footer>Generated by Tremor ").append(esc(TremorVersion.current()))
                .append(" · feel the shake before you upgrade</footer>\n</main></body></html>\n");
        out.print(h);
        out.flush();
    }

    private static void card(StringBuilder h, String cls, int n, String label) {
        h.append("<div class=\"card ").append(cls).append("\"><div class=\"n\">").append(n)
                .append("</div><div class=\"l\">").append(esc(label)).append("</div></div>");
    }

    private static void section(StringBuilder h, CheckResult r, String title, List<Finding> findings, boolean open) {
        if (findings.isEmpty()) {
            return;
        }
        h.append("<details").append(open ? " open" : "").append("><summary>").append(esc(title))
                .append(" (").append(findings.size()).append(")</summary>\n");
        Map<String, List<Finding>> groups = new LinkedHashMap<>();
        findings.stream()
                .sorted(Comparator.comparing((Finding f) -> !f.origin().isProject())
                        .thenComparing(f -> f.origin().label())
                        .thenComparing(f -> f.location().sourcePath())
                        .thenComparingInt(f -> f.location().line()))
                .forEach(f -> groups.computeIfAbsent(f.origin().isProject() ? "Your project"
                        : f.origin().label() + " (library)", k -> new java.util.ArrayList<>()).add(f));
        for (var g : groups.entrySet()) {
            h.append("<div class=\"group\"><h3>").append(esc(g.getKey())).append("</h3>");
            for (Finding f : g.getValue()) {
                h.append("<div class=\"item\"><div class=\"loc\">").append(esc(SourcePaths.fileAndLine(r.projectDir(), f)))
                        .append("</div><div class=\"what\">").append(esc(f.action())).append(", which ")
                        .append(esc(f.reason())).append("</div>");
                if (f.consequence() != null) {
                    h.append("<div class=\"why\">→ ").append(esc(f.consequence())).append("</div>");
                }
                if (f.hint() != null) {
                    h.append("<div class=\"hint\">💡 ").append(esc(f.hint())).append("</div>");
                }
                h.append("</div>");
            }
            h.append("</div>\n");
        }
        h.append("</details>\n");
    }

    private static void mediation(StringBuilder h, List<MediationIssue> issues) {
        if (issues.isEmpty()) {
            return;
        }
        boolean open = issues.stream().anyMatch(m -> m.risk() != MediationIssue.Risk.LOW);
        h.append("<details").append(open ? " open" : "").append("><summary>🔀 Version changes in the tree (")
                .append(issues.size()).append(")</summary><div class=\"group\">");
        issues.stream().sorted(Comparator.comparing(MediationIssue::risk)).forEach(m -> h
                .append("<div class=\"item\"><span class=\"risk ").append(m.risk().name()).append("\">")
                .append(m.risk().name()).append("</span>").append(esc(m.explanation())).append("</div>"));
        h.append("</div></details>\n");
    }

    static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '&' -> b.append("&amp;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&#39;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }
}
