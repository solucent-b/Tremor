package dev.tremor.cli;

/** Process exit codes, chosen so Tremor can gate a CI build. */
public final class ExitCodes {

    /** No breaking changes affect the project. */
    public static final int SAFE = 0;
    /** At least one break was found. */
    public static final int BREAKS_FOUND = 1;
    /** Tremor itself failed (bad arguments, network error, bug). */
    public static final int TOOL_ERROR = 2;

    private ExitCodes() {
    }
}
