package dev.tremor.resolve;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tremor.project.Gav;
import dev.tremor.project.ProjectModel;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("network")
class MavenCentralTest {

    @Test
    void resolvesRealTreeFromCentral(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId><artifactId>app</artifactId><version>1</version>
                  <dependencies>
                    <dependency><groupId>com.google.guava</groupId><artifactId>guava</artifactId>
                      <version>32.1.0-jre</version></dependency>
                  </dependencies>
                </project>
                """);
        try (MavenResolver resolver = new MavenResolver(ResolverConfig.defaults())) {
            ProjectModel project = new ProjectLoader(resolver).load(dir);
            DependencyTree tree = new DependencyTreeResolver(resolver).resolve(project);

            assertThat(tree.selected()).containsKeys("com.google.guava:guava", "com.google.guava:failureaccess");
            Path jar = resolver.resolveJar(new Gav("com.google.guava", "guava", "32.1.0-jre"));
            assertThat(jar).exists().hasFileName("guava-32.1.0-jre.jar");
        }
    }
}
