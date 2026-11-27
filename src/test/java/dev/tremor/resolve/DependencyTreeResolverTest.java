package dev.tremor.resolve;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tremor.project.Gav;
import dev.tremor.project.ProjectModel;
import dev.tremor.testing.TestRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DependencyTreeResolverTest {

    @TempDir
    Path temp;

    private TestRepository repo;
    private MavenResolver resolver;

    @BeforeEach
    void setUp() {
        repo = new TestRepository(temp.resolve("remote"));
        repo.library(new Gav("org.target", "target", "1.0"))
                .library(new Gav("org.target", "target", "2.0"))
                .library(new Gav("org.target", "target", "3.0"))
                .library(new Gav("org.lib", "uses-old", "1.0"), "org.target:target:1.0")
                .library(new Gav("org.lib", "uses-new", "1.0"), "org.target:target:3.0")
                .library(new Gav("org.lib", "deep", "1.0"), "org.lib:uses-old:1.0");
        resolver = new MavenResolver(new ResolverConfig(temp.resolve("cache"), List.of(repo.url())));
    }

    @AfterEach
    void tearDown() {
        resolver.close();
    }

    @Test
    void collectsTransitiveDependencies() throws Exception {
        ProjectModel project = load("""
                <dependency><groupId>org.lib</groupId><artifactId>deep</artifactId><version>1.0</version></dependency>
                """);

        DependencyTree tree = new DependencyTreeResolver(resolver).resolve(project);

        assertThat(tree.selected()).containsKeys("org.lib:deep", "org.lib:uses-old", "org.target:target");
        assertThat(tree.selected("org.target", "target")).map(Gav::version).contains("1.0");
        assertThat(tree.pathTo("org.target", "target")).extracting(TreeNode::key)
                .containsExactly("org.lib:deep", "org.lib:uses-old", "org.target:target");
    }

    @Test
    void nearestWinsAndLosersAreKept() throws Exception {
        ProjectModel project = load("""
                <dependency><groupId>org.lib</groupId><artifactId>uses-old</artifactId><version>1.0</version></dependency>
                <dependency><groupId>org.lib</groupId><artifactId>uses-new</artifactId><version>1.0</version></dependency>
                """);

        DependencyTree tree = new DependencyTreeResolver(resolver).resolve(project);

        assertThat(tree.selected("org.target", "target")).map(Gav::version).contains("1.0");
        List<DependencyTree.Edge> edges = tree.edgesTo("org.target", "target");
        assertThat(edges).hasSize(2);
        TreeNode fromNew = edges.stream().filter(e -> e.parent().key().equals("org.lib:uses-new"))
                .findFirst().orElseThrow().child();
        assertThat(fromNew.requestedVersion()).isEqualTo("3.0");
        assertThat(fromNew.selectedVersion()).isEqualTo("1.0");
        assertThat(fromNew.isMediated()).isTrue();
    }

    @Test
    void upgradeForcesVersionEverywhere() throws Exception {
        ProjectModel project = load("""
                <dependency><groupId>org.lib</groupId><artifactId>uses-old</artifactId><version>1.0</version></dependency>
                """);

        DependencyTree after = new DependencyTreeResolver(resolver)
                .resolveUpgraded(project, "org.target", "target", "2.0");

        assertThat(after.selected("org.target", "target")).map(Gav::version).contains("2.0");
        TreeNode node = after.edgesTo("org.target", "target").get(0).child();
        assertThat(node.requestedVersion()).isEqualTo("1.0");
        assertThat(node.isMediated()).isTrue();
    }

    @Test
    void listsNewerVersions() {
        assertThat(resolver.newerVersions("org.target", "target", "1.0")).containsExactly("2.0", "3.0");
    }

    @Test
    void loaderExpandsImportedBomsAndRemoteParents() throws Exception {
        repo.rawPom(new Gav("org.bom", "bom", "1.0"), """
                <packaging>pom</packaging>
                <dependencyManagement><dependencies>
                  <dependency><groupId>org.target</groupId><artifactId>target</artifactId><version>2.0</version></dependency>
                </dependencies></dependencyManagement>
                """);
        repo.rawPom(new Gav("org.parent", "parent", "1.0"), """
                <packaging>pom</packaging>
                <properties><lib.version>1.0</lib.version></properties>
                """);
        Path dir = Files.createDirectories(temp.resolve("project"));
        Files.writeString(dir.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>org.parent</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>app</artifactId>
                  <dependencyManagement><dependencies>
                    <dependency><groupId>org.bom</groupId><artifactId>bom</artifactId><version>1.0</version>
                      <type>pom</type><scope>import</scope></dependency>
                  </dependencies></dependencyManagement>
                  <dependencies>
                    <dependency><groupId>org.target</groupId><artifactId>target</artifactId></dependency>
                    <dependency><groupId>org.lib</groupId><artifactId>uses-old</artifactId><version>${lib.version}</version></dependency>
                  </dependencies>
                </project>
                """);

        ProjectModel project = new ProjectLoader(resolver).load(dir);

        assertThat(project.findDependency("org.target", "target").orElseThrow().version()).isEqualTo("2.0");
        assertThat(project.findDependency("org.lib", "uses-old").orElseThrow().version()).isEqualTo("1.0");
        assertThat(project.warnings()).isEmpty();
    }

    private ProjectModel load(String dependencies) throws Exception {
        Path dir = Files.createDirectories(temp.resolve("project"));
        Files.writeString(dir.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId><artifactId>app</artifactId><version>1</version>
                  <dependencies>%s</dependencies>
                </project>
                """.formatted(dependencies));
        return new ProjectLoader(resolver).load(dir);
    }
}
