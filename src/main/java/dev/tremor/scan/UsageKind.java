package dev.tremor.scan;

/** How a piece of compiled code refers to another class or member. */
public enum UsageKind {
    METHOD_CALL("calls"),
    METHOD_REFERENCE("uses method reference"),
    FIELD_READ("reads"),
    FIELD_WRITE("writes"),
    NEW_INSTANCE("creates"),
    EXTENDS("extends"),
    IMPLEMENTS("implements"),
    CAST("casts to"),
    INSTANCEOF("checks instanceof"),
    CLASS_LITERAL("uses class literal"),
    ANNOTATION("is annotated with"),
    CATCH("catches"),
    TYPE_REFERENCE("uses type");

    private final String verb;

    UsageKind(String verb) {
        this.verb = verb;
    }

    /** Verb used in human-readable messages, e.g. "calls". */
    public String verb() {
        return verb;
    }

    public boolean isMemberUsage() {
        return this == METHOD_CALL || this == METHOD_REFERENCE || this == FIELD_READ || this == FIELD_WRITE
                || this == NEW_INSTANCE;
    }
}
