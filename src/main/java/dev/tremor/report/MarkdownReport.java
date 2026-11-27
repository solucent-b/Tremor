package dev.tremor.report;

import dev.tremor.analysis.CheckResult;
import dev.tremor.analysis.Finding;
import dev.tremor.analysis.MediationIssue;
import dev.tremor.analysis.UpdateRow;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/** A short Markdown summary, sized for a pull request comment. */
public final class MarkdownReport {

    /** Hidden marker so the GitHub Action can find and update its own comment. */
    public static final String MARKER = "<!-- tremor-report -->";

    private static final int MAX_ITEMS = 10;

    public void write(CheckResult r, PrintWriter out) {
        out.println(MARKER);
        String upgrade = "upgrading `" + r.from().artifactId() + "` " + r.from().version() + " → " + r.to().version();
        List<String> items = new ArrayList<>();
        for (Finding f : r.breaks()) {
            items.add(item(r, f));
        }
        for (MediationIssue m : r.mediation()) {
            if (m.isBreak() && r.breaks().stream().noneMatch(b -> m.library().equals(b.origin().library()))) {
                items.add(m.explanation());
            }
        }
        if (!items.isEmpty()) {
            int n = items.size();
            out.println("⚠️ **Tremor:** " + n + (n == 1 ? " thing will break" : " things will break")
                    + " when " + upgrade + ".");
            list(items, out);
        } else if (!r.warnings().isEmpty() || hasRiskyMediation(r)) {
            List<String> warnings = new ArrayList<>(r.warnings().stream().map(f -> item(r, f)).toList());
            r.mediation().stream().filter(m -> m.risk() == MediationIssue.Risk.RISKY)
                    .forEach(m -> warnings.add(m.explanation()));
            out.println("✅ **Tremor:** " + upgrade + " has no known breaks for this project, but "
                    + Summary.plural(warnings.size(), "thing") + " deserve" + (warnings.size() == 1 ? "s" : "")
                    + " a look:");
            list(warnings, out);
        } else {
            out.println("✅ **Tremor:** " + upgrade + " is safe for this project.");
        }
        out.println();
        out.println("<sub>" + Summary.of(r).line() + " · " + Summary.plural(r.scannedLibraries().size(),
                "other library") + " scanned · Tremor checks compiled bytecode; reflection and framework"
                + " wiring are not covered.</sub>");
        out.flush();
    }

    /** {@code --all-updates} as a Markdown table. */
    public void updates(List<UpdateRow> rows, PrintWriter out) {
        out.println(MARKER);
        out.println("| Dependency | Current | Latest | Verdict |");
        out.println("|---|---|---|---|");
        for (UpdateRow r : rows) {
            String verdict = switch (r.verdict()) {
                case SAFE -> "✅ safe";
                case RISKY -> "⚠️ risky";
                case BREAKS -> "❌ " + Summary.plural(r.result().breaks().size(), "break");
                case UP_TO_DATE -> "up to date";
                case ERROR -> "error";
            };
            out.println("| `" + r.key() + "` | " + r.current() + " | " + (r.latest() == null ? "-" : r.latest())
                    + " | " + verdict + " |");
        }
        out.flush();
    }

    private static String item(CheckResult r, Finding f) {
        String where = "`" + SourcePaths.fileAndLine(r.projectDir(), f) + "`";
        if (!f.origin().isProject()) {
            where = "**" + f.origin().label() + "** " + where;
        }
        return where + " " + code(f.action()) + ", which " + f.reason()
                + (f.hint() == null ? "" : " — " + f.hint());
    }

    /** Wraps the Java part of an action ("calls Cache.get(Object)") in backticks. */
    private static String code(String action) {
        int space = action.indexOf(' ');
        return space < 0 ? action : action.substring(0, space) + " `" + action.substring(space + 1) + "`";
    }

    private static void list(List<String> items, PrintWriter out) {
        out.println();
        items.stream().limit(MAX_ITEMS).forEach(i -> out.println("- " + i));
        if (items.size() > MAX_ITEMS) {
            out.println("- …and " + (items.size() - MAX_ITEMS) + " more (see the full report).");
        }
    }

    private static boolean hasRiskyMediation(CheckResult r) {
        return r.mediation().stream().anyMatch(m -> m.risk() == MediationIssue.Risk.RISKY);
    }
}
