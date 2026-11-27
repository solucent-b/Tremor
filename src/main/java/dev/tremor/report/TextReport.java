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

/** The human-readable terminal report, grouped by ❌ breaks, ⚠️ warnings and ✅ safe. */
public final class TextReport {

    private final boolean color;

    public TextReport(boolean color) {
        this.color = color;
    }

    public void write(CheckResult r, PrintWriter out) {
        out.println(bold("Tremor " + TremorVersion.current()) + dim(" · ")
                + r.from().groupId() + ":" + r.from().artifactId() + " " + r.from().version()
                + " → " + bold(r.to().version()));
        out.println(dim("Project: " + r.project() + "   Libraries scanned: " + r.scannedLibraries().size()));
        out.println();

        List<Finding> breaks = r.breaks();
        List<Finding> warnings = r.warnings();
        if (!breaks.isEmpty()) {
            out.println(red(bold("❌ Breaks (" + breaks.size() + ")")));
            findings(r, breaks, out);
        }
        if (!warnings.isEmpty()) {
            out.println(yellow(bold("⚠️  Warnings (" + warnings.size() + ")")));
            findings(r, warnings, out);
        }
        List<MediationIssue> notable = r.mediation().stream()
                .filter(m -> m.risk() != MediationIssue.Risk.LOW).toList();
        if (!r.mediation().isEmpty()) {
            out.println(bold("🔀 Version changes in the tree (Maven picks one version: \"nearest wins\")"));
            for (MediationIssue m : notable) {
                String mark = m.isBreak() ? red("❌") : yellow("⚠️ ");
                out.println("  " + mark + " " + m.explanation());
            }
            int low = r.mediation().size() - notable.size();
            if (low > 0) {
                out.println(dim("  " + low + " low-risk version change" + (low == 1 ? "" : "s")
                        + " (same major version, or scanned with no break). See --format json for details."));
            }
            out.println();
        }
        if (breaks.isEmpty() && warnings.isEmpty()) {
            out.println(green(bold("✅ Safe: nothing you use changed.")));
            if (r.apiChanges().breaking() > 0) {
                out.println(dim("   The library has " + Summary.plural(r.apiChanges().breaking(), "breaking change")
                        + ", but none of them touch code Tremor can see."));
            }
            out.println();
        }
        if (!r.notes().isEmpty()) {
            out.println(dim("Notes"));
            r.notes().forEach(n -> out.println(dim("  • " + n)));
            out.println();
        }
        Summary s = Summary.of(r);
        String line = s.line();
        out.println(switch (s.verdict()) {
            case "breaks" -> red(bold("❌ " + line));
            case "warnings" -> yellow(bold("⚠️  " + line));
            default -> green(bold("✅ " + line + " — safe to upgrade"));
        });
        out.flush();
    }

    private void findings(CheckResult r, List<Finding> findings, PrintWriter out) {
        Map<String, List<Finding>> byOrigin = new LinkedHashMap<>();
        findings.stream()
                .sorted(Comparator.comparing((Finding f) -> !f.origin().isProject())
                        .thenComparing(f -> f.origin().label())
                        .thenComparing(f -> f.location().sourcePath())
                        .thenComparingInt(f -> f.location().line()))
                .forEach(f -> byOrigin.computeIfAbsent(f.origin().isProject() ? "Your project"
                        : f.origin().label() + " (library)", k -> new java.util.ArrayList<>()).add(f));
        for (var group : byOrigin.entrySet()) {
            out.println("  " + bold(group.getKey()));
            for (Finding f : group.getValue()) {
                out.println("    " + cyan(SourcePaths.fileAndLine(r.projectDir(), f)));
                out.println("      " + f.action() + ", which " + f.reason());
                if (f.consequence() != null) {
                    out.println("      " + dim("→ " + f.consequence()));
                }
                if (f.hint() != null) {
                    out.println("      💡 " + f.hint());
                }
            }
        }
        out.println();
    }

    private String bold(String s) {
        return ansi("1", s);
    }

    private String dim(String s) {
        return ansi("2", s);
    }

    private String red(String s) {
        return ansi("31", s);
    }

    private String yellow(String s) {
        return ansi("33", s);
    }

    private String green(String s) {
        return ansi("32", s);
    }

    private String cyan(String s) {
        return ansi("36", s);
    }

    private String ansi(String code, String s) {
        return color ? "\u001b[" + code + "m" + s + "\u001b[0m" : s;
    }
}
