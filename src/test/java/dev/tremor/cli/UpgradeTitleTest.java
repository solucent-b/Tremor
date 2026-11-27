package dev.tremor.cli;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class UpgradeTitleTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "Bump com.google.guava:guava from 32.1.0-jre to 33.0.0-jre|com.google.guava:guava|32.1.0-jre|33.0.0-jre|",
        "build(deps): bump com.google.guava:guava from 32.1.0-jre to 33.0.0-jre|com.google.guava:guava|32.1.0-jre|33.0.0-jre|",
        "Bump commons-io:commons-io from 2.4 to 2.16.1 in /backend|commons-io:commons-io|2.4|2.16.1|/backend",
        "chore(deps): Bump org.slf4j:slf4j-api from 1.7.36 to 2.0.13 (#42)|org.slf4j:slf4j-api|1.7.36|2.0.13|",
        "Update dependency com.google.guava:guava to v33.0.0-jre|com.google.guava:guava||33.0.0-jre|",
        "fix(deps): update dependency com.fasterxml.jackson.core:jackson-databind to v2.17.2 (#7)"
                + "|com.fasterxml.jackson.core:jackson-databind||2.17.2|",
    })
    void parsesBotTitles(String title, String dependency, String from, String to, String directory) {
        UpgradeTitle u = UpgradeTitle.parse(title).orElseThrow();

        assertThat(u.dependency()).isEqualTo(dependency);
        assertThat(u.from()).isEqualTo(from);
        assertThat(u.to()).isEqualTo(to);
        assertThat(u.directory()).isEqualTo(directory);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bump the maven group with 3 updates", "Fix typo in README", "Update Jackson to v2.17.2"})
    void ignoresTitlesItCannotRead(String title) {
        assertThat(UpgradeTitle.parse(title)).isEmpty();
    }

    @Test
    void commandPrintsKeyValueLines() {
        CliRunner r = CliRunner.run("detect-upgrade", "--title",
                "Bump com.google.guava:guava from 32.1.0-jre to 33.0.0-jre in /app");

        assertThat(r.exitCode()).isZero();
        assertThat(r.out()).contains("detected=true", "dependency=com.google.guava:guava", "from=32.1.0-jre",
                "to=33.0.0-jre", "directory=/app");
        assertThat(CliRunner.run("detect-upgrade", "--title", "hello").out().strip()).isEqualTo("detected=false");
    }
}
