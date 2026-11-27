package dev.tremor.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.tremor.analysis.MediationIssue;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonReportTest {

    @Test
    void followsTheDocumentedSchema(@TempDir Path dir) throws Exception {
        var risky = new MediationIssue(TextReportTest.LIB_Y, "com.google.guava:guava", "34.0", "33.0.0-jre",
                MediationIssue.Risk.RISKY, "older");
        StringWriter s = new StringWriter();
        new JsonReport().write(TextReportTest.result(dir,
                List.of(TextReportTest.projectBreak(), TextReportTest.libraryBreak(), TextReportTest.warning()),
                List.of(risky)), new PrintWriter(s));

        JsonNode json = new ObjectMapper().readTree(s.toString());

        assertThat(json.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(json.get("tool").get("name").asText()).isEqualTo("tremor");
        assertThat(json.get("project").get("artifactId").asText()).isEqualTo("app");
        assertThat(json.get("dependency").get("from").asText()).isEqualTo("20.0");
        assertThat(json.get("dependency").get("to").asText()).isEqualTo("33.0.0-jre");
        assertThat(json.get("verdict").asText()).isEqualTo("breaks");
        assertThat(json.get("summary").get("breaks").asInt()).isEqualTo(2);
        assertThat(json.get("summary").get("text").asText())
                .isEqualTo("2 breaks, 1 warning across 2 files and 1 library");

        JsonNode b = json.get("breaks").get(0);
        assertThat(b.get("severity").asText()).isEqualTo("BREAKING");
        assertThat(b.get("kind").asText()).isEqualTo("REMOVED_METHOD");
        assertThat(b.get("origin").get("type").asText()).isEqualTo("project");
        assertThat(b.get("file").asText()).isEqualTo("com/example/UserService.java");
        assertThat(b.get("line").asInt()).isEqualTo(84);
        assertThat(b.get("className").asText()).isEqualTo("com.example.UserService");
        assertThat(b.get("message").asText())
                .isEqualTo("com/example/UserService.java:84 calls Cache.getIfPresent(Object), which was removed");
        assertThat(json.get("breaks").get(1).get("origin").get("artifactId").asText()).isEqualTo("library-y");
        assertThat(json.get("warnings")).hasSize(1);
        assertThat(json.get("versionChanges").get(0).get("risk").asText()).isEqualTo("RISKY");
        assertThat(json.get("apiChanges").get("breaking").asInt()).isEqualTo(12);
        assertThat(json.get("scannedLibraries").get(0).get("version").asText()).isEqualTo("1.4.2");
        assertThat(json.get("notes").get(0).asText()).isEqualTo("Some note.");
    }
}
