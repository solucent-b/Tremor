package dev.tremor.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.tremor.analysis.CheckResult;
import dev.tremor.analysis.Finding;
import dev.tremor.analysis.MediationIssue;
import dev.tremor.bytecode.Names;
import dev.tremor.cli.TremorVersion;
import dev.tremor.project.Gav;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * Machine-readable report. The schema is documented in {@code docs/json-format.md}; fields are
 * only ever added within a schema version, never renamed or removed.
 */
public final class JsonReport {

    public static final int SCHEMA_VERSION = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public void write(CheckResult r, PrintWriter out) {
        try {
            out.println(MAPPER.writeValueAsString(toJson(r)));
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public ObjectNode toJson(CheckResult r) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        ObjectNode tool = root.putObject("tool");
        tool.put("name", "tremor");
        tool.put("version", TremorVersion.current());

        ObjectNode project = gav(root.putObject("project"), r.project());
        project.put("path", r.projectDir() == null ? null : r.projectDir().toAbsolutePath().normalize().toString());

        ObjectNode dep = root.putObject("dependency");
        dep.put("groupId", r.from().groupId());
        dep.put("artifactId", r.from().artifactId());
        dep.put("from", r.from().version());
        dep.put("to", r.to().version());

        Summary s = Summary.of(r);
        root.put("verdict", s.verdict());
        ObjectNode summary = root.putObject("summary");
        summary.put("breaks", s.breaks());
        summary.put("warnings", s.warnings());
        summary.put("files", s.files());
        summary.put("libraries", s.libraries());
        summary.put("riskyVersionChanges", s.riskyVersionChanges());
        summary.put("text", s.line());

        findings(root.putArray("breaks"), r, r.breaks());
        findings(root.putArray("warnings"), r, r.warnings());

        ArrayNode versions = root.putArray("versionChanges");
        for (MediationIssue m : r.mediation()) {
            ObjectNode n = versions.addObject();
            gav(n.putObject("library"), m.library());
            n.put("dependency", m.dependency());
            n.put("requested", m.requested());
            n.put("resolved", m.resolved());
            n.put("risk", m.risk().name());
            n.put("explanation", m.explanation());
        }

        ObjectNode api = root.putObject("apiChanges");
        api.put("breaking", r.apiChanges().breaking());
        api.put("warnings", r.apiChanges().warnings());

        ArrayNode scanned = root.putArray("scannedLibraries");
        r.scannedLibraries().forEach(g -> gav(scanned.addObject(), g));
        ArrayNode notes = root.putArray("notes");
        r.notes().forEach(notes::add);
        return root;
    }

    private static void findings(ArrayNode array, CheckResult r, List<Finding> findings) {
        for (Finding f : findings) {
            ObjectNode n = array.addObject();
            n.put("severity", f.severity().name());
            n.put("kind", f.kind() == null ? null : f.kind().name());
            ObjectNode origin = n.putObject("origin");
            if (f.origin().isProject()) {
                origin.put("type", "project");
            } else {
                origin.put("type", "library");
                gav(origin, f.origin().library());
            }
            n.put("file", SourcePaths.file(r.projectDir(), f));
            n.put("line", f.location().line());
            n.put("className", Names.display(f.location().className()));
            n.put("member", f.location().member());
            n.put("action", f.action());
            n.put("reason", f.reason());
            n.put("consequence", f.consequence());
            n.put("hint", f.hint());
            n.put("message", SourcePaths.fileAndLine(r.projectDir(), f) + " " + f.action() + ", which " + f.reason());
        }
    }

    private static ObjectNode gav(ObjectNode node, Gav gav) {
        node.put("groupId", gav.groupId());
        node.put("artifactId", gav.artifactId());
        node.put("version", gav.version());
        return node;
    }
}
