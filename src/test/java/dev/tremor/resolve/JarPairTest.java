package dev.tremor.resolve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tremor.project.Gav;
import dev.tremor.testing.TestRepository;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JarPairTest {

    @TempDir
    Path temp;

    private MavenResolver resolver() {
        TestRepository repo = new TestRepository(temp.resolve("remote"));
        repo.library(new Gav("org.t", "t", "1.0")).library(new Gav("org.t", "t", "1.1"))
                .library(new Gav("org.t", "t", "2.0"));
        return new MavenResolver(new ResolverConfig(temp.resolve("cache"), List.of(repo.url())));
    }

    @Test
    void downloadsBothJarsIntoTheCache() {
        try (MavenResolver resolver = resolver()) {
            JarPair pair = JarPair.fetch(resolver, "org.t", "t", "1.0", "2.0");

            assertThat(pair.oldJar()).exists().startsWith(temp.resolve("cache"));
            assertThat(pair.newJar()).exists().hasFileName("t-2.0.jar");
            assertThat(pair.newGav()).isEqualTo(new Gav("org.t", "t", "2.0"));
        }
    }

    @Test
    void missingTargetVersionSuggestsRealOnes() {
        try (MavenResolver resolver = resolver()) {
            assertThatThrownBy(() -> JarPair.fetch(resolver, "org.t", "t", "1.0", "9.9"))
                    .isInstanceOf(ResolutionException.class)
                    .hasMessageContaining("version 9.9 exists")
                    .hasMessageContaining("1.1, 2.0");
        }
    }
}
