package dev.tremor.analysis;

import dev.tremor.project.Gav;

/**
 * A library that, because of Maven's "nearest wins" rule, gets a different version of a dependency
 * than the one it declared.
 *
 * @param library the library that declared the dependency
 * @param dependency the dependency's {@code groupId:artifactId}
 * @param requested the version the library asked for
 * @param resolved the version Maven will put on the classpath
 * @param risk how risky the mismatch is
 * @param explanation plain-words description
 */
public record MediationIssue(
        Gav library,
        String dependency,
        String requested,
        String resolved,
        Risk risk,
        String explanation) {

    public enum Risk {
        /** The library's own code uses something that is gone in the resolved version. */
        BREAKS,
        /** Major version jump or a downgrade: likely trouble even if Tremor saw no direct break. */
        RISKY,
        /** Minor difference; usually fine. */
        LOW
    }

    public boolean isBreak() {
        return risk == Risk.BREAKS;
    }
}
