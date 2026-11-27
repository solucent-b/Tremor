package dev.tremor.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PomReaderTest {

    private final PomReader reader = new PomReader();

    @Test
    void readsCoordinatesAndDirectDependencies() {
        ProjectModel model = reader.read(TestProjects.path("simple"));

        assertThat(model.gav()).isEqualTo(new Gav("com.example", "simple", "1.0.0"));
        assertThat(model.dependencies()).extracting(DeclaredDependency::key).containsExactly(
                "com.google.guava:guava",
                "commons-io:commons-io",
                "com.fasterxml.jackson.core:jackson-databind",
                "com.example:sibling",
                "com.example:loopy",
                "org.junit.jupiter:junit-jupiter");
    }

    @Test
    void resolvesVersionFromProperty() {
        ProjectModel model = reader.read(TestProjects.path("simple"));

        assertThat(model.findDependency("com.google.guava", "guava"))
                .map(DeclaredDependency::version).contains("20.0");
    }

    @Test
    void resolvesProjectVersionExpression() {
        ProjectModel model = reader.read(TestProjects.path("simple"));

        assertThat(model.findDependency("com.example", "sibling"))
                .map(DeclaredDependency::version).contains("1.0.0");
    }

    @Test
    void fillsVersionAndScopeFromDependencyManagement() {
        DeclaredDependency io = reader.read(TestProjects.path("simple"))
                .findDependency("commons-io", "commons-io").orElseThrow();

        assertThat(io.version()).isEqualTo("2.4");
        assertThat(io.scope()).isEqualTo("runtime");
    }

    @Test
    void reportsImportedBomsAndWarnsAboutVersionsTheyWouldSupply() {
        ProjectModel model = reader.read(TestProjects.path("simple"));

        assertThat(model.importedBoms()).extracting(DeclaredDependency::key)
                .containsExactly("com.fasterxml.jackson:jackson-bom");
        assertThat(model.findDependency("com.fasterxml.jackson.core", "jackson-databind"))
                .map(DeclaredDependency::version).isEqualTo(Optional.empty());
        assertThat(model.warnings()).anyMatch(w -> w.contains("jackson-databind"));
    }

    @Test
    void cyclicPropertiesLeaveVersionUnknownInsteadOfHanging() {
        ProjectModel model = reader.read(TestProjects.path("simple"));

        assertThat(model.findDependency("com.example", "loopy").orElseThrow().version()).isNull();
    }

    @Test
    void keepsTestScope() {
        ProjectModel model = reader.read(TestProjects.path("simple"));

        assertThat(model.findDependency("org.junit.jupiter", "junit-jupiter").orElseThrow().scope())
                .isEqualTo("test");
    }

    @Test
    void inheritsFromLocalParentAndChildPropertiesWin() {
        ProjectModel model = reader.read(TestProjects.path("with-parent/child"));

        assertThat(model.gav()).isEqualTo(new Gav("com.example", "child", "2.0.0"));
        assertThat(model.parent()).isEqualTo(new Gav("com.example", "parent", "2.0.0"));
        assertThat(model.findDependency("com.google.guava", "guava"))
                .map(DeclaredDependency::version).contains("32.1.0-jre");
        assertThat(model.findDependency("com.example", "child-api"))
                .map(DeclaredDependency::version).contains("2.0.0");
        assertThat(model.findDependency("org.slf4j", "slf4j-api"))
                .map(DeclaredDependency::version).contains("1.7.36");
        assertThat(model.warnings()).isEmpty();
    }

    @Test
    void usesLocatorForParentNotOnDisk() {
        Path parentPom = TestProjects.path("with-parent/pom.xml");
        PomReader withLocator = new PomReader(gav -> gav.artifactId().equals("spring-boot-starter-parent")
                ? Optional.of(parentPom) : Optional.empty());

        ProjectModel model = withLocator.read(TestProjects.path("orphan"));

        assertThat(model.findDependency("com.google.guava", "guava"))
                .map(DeclaredDependency::version).contains("31.0-jre");
    }

    @Test
    void warnsWhenParentCannotBeFound() {
        ProjectModel model = reader.read(TestProjects.path("orphan"));

        assertThat(model.gav().groupId()).isEqualTo("org.springframework.boot");
        assertThat(model.warnings()).anyMatch(w -> w.contains("spring-boot-starter-parent"));
        assertThat(model.findDependency("com.google.guava", "guava").orElseThrow().version()).isNull();
    }

    @Test
    void missingPomGivesHelpfulError(@TempDir Path empty) {
        assertThatThrownBy(() -> reader.read(empty))
                .isInstanceOf(ProjectReadException.class)
                .hasMessageContaining("No pom.xml found");
    }

    @Test
    void malformedPomGivesHelpfulError(@TempDir Path dir) throws Exception {
        java.nio.file.Files.writeString(dir.resolve("pom.xml"), "<project><oops>");

        assertThatThrownBy(() -> reader.read(dir))
                .isInstanceOf(ProjectReadException.class)
                .hasMessageContaining("Could not read");
    }
}
