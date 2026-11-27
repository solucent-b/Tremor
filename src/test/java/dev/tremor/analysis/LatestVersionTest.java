package dev.tremor.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class LatestVersionTest {

    @Test
    void picksNewestStableWithTheSameFlavour() {
        List<String> guava = List.of("32.1.0-jre", "32.1.0-android", "33.0.0-jre", "33.0.0-android",
                "33.1.0-jre", "34.0.0-rc1-jre", "33.1.0-android");

        assertThat(LatestVersion.pick("32.1.0-jre", guava)).contains("33.1.0-jre");
        assertThat(LatestVersion.pick("32.1.0-android", guava)).contains("33.1.0-android");
    }

    @Test
    void skipsPreReleasesAndOlderVersions() {
        assertThat(LatestVersion.pick("2.9.10", List.of("2.10.0", "2.17.2", "3.0.0-rc1", "2.18.0-SNAPSHOT")))
                .contains("2.17.2");
        assertThat(LatestVersion.pick("2.17.2", List.of("2.17.1"))).isEmpty();
    }

    @Test
    void treatsFinalAndReleaseMarkersAsPlainReleases() {
        assertThat(LatestVersion.pick("5.4.0.Final", List.of("6.0.0.Final", "6.1.0", "6.2.0.CR1")))
                .contains("6.1.0");
        assertThat(LatestVersion.flavour("33.0.0-jre")).isEqualTo("-jre");
        assertThat(LatestVersion.flavour("2.17.1")).isEmpty();
    }
}
