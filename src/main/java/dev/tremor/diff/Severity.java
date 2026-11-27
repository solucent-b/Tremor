package dev.tremor.diff;

/** How serious a change is. */
public enum Severity {
    /** Code compiled against the old version can fail at runtime (linkage error) or no longer compiles. */
    BREAKING,
    /** Still links, but deserves attention: deprecations, new checked exceptions. */
    WARNING
}
