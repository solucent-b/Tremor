package dev.tremor.project;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GradleBuildReaderTest {

    @TempDir
    Path dir;

    private ProjectModel read() {
        return new GradleBuildReader().read(dir);
    }

    private String version(ProjectModel m, String g, String a) {
        return m.findDependency(g, a).map(DeclaredDependency::version).orElse("<missing>");
    }

    @Test
    void groovyDslWithVariablesAndProperties() throws Exception {
        Files.writeString(dir.resolve("gradle.properties"), "jacksonVersion=2.9.10\n");
        Files.writeString(dir.resolve("settings.gradle"), "rootProject.name = 'shop'\n");
        Files.writeString(dir.resolve("build.gradle"), """
                plugins { id 'java' }
                group = 'com.example'
                version = '1.2.3'
                ext { guavaVersion = '20.0' }
                def ioVersion = "2.4"

                dependencies {
                    implementation 'com.google.guava:guava:' + guavaVersion
                    implementation "com.google.guava:guava:${guavaVersion}"
                    implementation "com.fasterxml.jackson.core:jackson-databind:$jacksonVersion"
                    runtimeOnly group: 'commons-io', name: 'commons-io', version: ioVersion
                    api group: 'org.slf4j', name: 'slf4j-api', version: '1.7.36'
                    testImplementation 'org.junit.jupiter:junit-jupiter:5.11.4'
                    // implementation 'commented:out:1.0'
                    compileOnly 'org.projectlombok:lombok:1.18.34'
                }
                """);

        ProjectModel m = read();

        assertThat(m.gav()).isEqualTo(new Gav("com.example", "shop", "1.2.3"));
        assertThat(version(m, "com.google.guava", "guava")).isEqualTo("20.0");
        assertThat(version(m, "com.fasterxml.jackson.core", "jackson-databind")).isEqualTo("2.9.10");
        assertThat(version(m, "org.slf4j", "slf4j-api")).isEqualTo("1.7.36");
        assertThat(m.findDependency("org.junit.jupiter", "junit-jupiter").orElseThrow().scope()).isEqualTo("test");
        assertThat(m.findDependency("org.projectlombok", "lombok").orElseThrow().scope()).isEqualTo("provided");
        assertThat(m.findDependency("commented", "out")).isEmpty();
        assertThat(m.warnings()).isEmpty();
    }

    @Test
    void mapNotationWithVariableIsResolved() throws Exception {
        Files.writeString(dir.resolve("build.gradle"), """
                def ioVersion = "2.4"
                dependencies { runtimeOnly group: 'commons-io', name: 'commons-io', version: "$ioVersion" }
                """);

        ProjectModel m = read();

        assertThat(version(m, "commons-io", "commons-io")).isEqualTo("2.4");
        assertThat(m.findDependency("commons-io", "commons-io").orElseThrow().scope()).isEqualTo("runtime");
    }

    @Test
    void kotlinDslWithVersionCatalogAndPlatform() throws Exception {
        Files.createDirectories(dir.resolve("gradle"));
        Files.writeString(dir.resolve("gradle/libs.versions.toml"), """
                [versions]
                guava = "32.1.0-jre"
                jackson = "2.17.2"

                [libraries]
                guava = { module = "com.google.guava:guava", version.ref = "guava" }
                jackson-bom = { group = "com.fasterxml.jackson", name = "jackson-bom", version.ref = "jackson" }
                jackson-databind = { module = "com.fasterxml.jackson.core:jackson-databind" }
                commons-io = "commons-io:commons-io:2.16.1"

                [plugins]
                shadow = { id = "com.gradleup.shadow", version = "8.3.0" }
                """);
        Files.writeString(dir.resolve("build.gradle.kts"), """
                plugins { java }
                dependencies {
                    implementation(platform(libs.jackson.bom))
                    implementation(libs.jackson.databind)
                    implementation(libs.guava)
                    implementation(libs.commons.io)
                    implementation("org.slf4j:slf4j-api:2.0.13")
                    implementation(group = "org.yaml", name = "snakeyaml", version = "2.2")
                }
                """);

        ProjectModel m = read();

        assertThat(version(m, "com.google.guava", "guava")).isEqualTo("32.1.0-jre");
        assertThat(version(m, "commons-io", "commons-io")).isEqualTo("2.16.1");
        assertThat(version(m, "org.slf4j", "slf4j-api")).isEqualTo("2.0.13");
        assertThat(version(m, "org.yaml", "snakeyaml")).isEqualTo("2.2");
        assertThat(m.findDependency("com.fasterxml.jackson.core", "jackson-databind").orElseThrow().version()).isNull();
        assertThat(m.importedBoms()).singleElement().satisfies(b -> {
            assertThat(b.key()).isEqualTo("com.fasterxml.jackson:jackson-bom");
            assertThat(b.version()).isEqualTo("2.17.2");
        });
    }

    @Test
    void dynamicVersionsAreReportedNotGuessed() throws Exception {
        Files.writeString(dir.resolve("build.gradle"), """
                dependencies { implementation 'com.google.guava:guava:33.+' }
                """);

        ProjectModel m = read();

        assertThat(m.findDependency("com.google.guava", "guava").orElseThrow().version()).isNull();
        assertThat(m.warnings()).anyMatch(w -> w.contains("33.+"));
    }

    @Test
    void catalogAccessorNormalisesDashesAndUnderscores() {
        assertThat(VersionCatalog.accessor("jackson-databind")).isEqualTo("jackson.databind");
        assertThat(VersionCatalog.accessor("commons_io")).isEqualTo("commons.io");
    }
}
