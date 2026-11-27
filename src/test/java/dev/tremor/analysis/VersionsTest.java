package dev.tremor.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class VersionsTest {

    @Test
    void comparesLikeMaven() {
        assertThat(Versions.compare("33.0.0-jre", "32.1.0-jre")).isPositive();
        assertThat(Versions.compare("2.9.10", "2.17.0")).isNegative();
        assertThat(Versions.compare("1.0", "1.0.0")).isZero();
    }

    @Test
    void majorVersion() {
        assertThat(Versions.major("33.0.0-jre")).isEqualTo(33);
        assertThat(Versions.isMajorChange("2.9.10", "2.17.0")).isFalse();
        assertThat(Versions.isMajorChange("20.0", "33.0.0-jre")).isTrue();
        assertThat(Versions.major("RELEASE")).isEqualTo(-1);
    }

    @Test
    void preReleases() {
        assertThat(Versions.isPreRelease("2.0.0-beta1")).isTrue();
        assertThat(Versions.isPreRelease("5.0.0-M2")).isTrue();
        assertThat(Versions.isPreRelease("1.0-SNAPSHOT")).isTrue();
        assertThat(Versions.isPreRelease("3.0.0-rc.1")).isTrue();
        assertThat(Versions.isPreRelease("33.0.0-jre")).isFalse();
        assertThat(Versions.isPreRelease("2.17.1")).isFalse();
        assertThat(Versions.isPreRelease("1.9.22.Final")).isFalse();
    }
}
