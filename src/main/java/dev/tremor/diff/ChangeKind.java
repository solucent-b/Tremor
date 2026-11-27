package dev.tremor.diff;

/** The kinds of API change Tremor detects, with their default severity. */
public enum ChangeKind {
    REMOVED_CLASS(Severity.BREAKING),
    CLASS_LESS_VISIBLE(Severity.BREAKING),
    CLASS_NOW_FINAL(Severity.BREAKING),
    CLASS_NOW_ABSTRACT(Severity.BREAKING),
    CLASS_KIND_CHANGED(Severity.BREAKING),
    REMOVED_SUPERTYPE(Severity.BREAKING),
    REMOVED_METHOD(Severity.BREAKING),
    REMOVED_CONSTRUCTOR(Severity.BREAKING),
    PARAMETERS_CHANGED(Severity.BREAKING),
    RETURN_TYPE_CHANGED(Severity.BREAKING),
    METHOD_LESS_VISIBLE(Severity.BREAKING),
    METHOD_NOW_FINAL(Severity.BREAKING),
    METHOD_NOW_ABSTRACT(Severity.BREAKING),
    METHOD_STATIC_CHANGED(Severity.BREAKING),
    ABSTRACT_METHOD_ADDED(Severity.BREAKING),
    REMOVED_FIELD(Severity.BREAKING),
    FIELD_TYPE_CHANGED(Severity.BREAKING),
    FIELD_LESS_VISIBLE(Severity.BREAKING),
    FIELD_NOW_FINAL(Severity.BREAKING),
    FIELD_STATIC_CHANGED(Severity.BREAKING),
    NEW_CHECKED_EXCEPTION(Severity.WARNING),
    DEPRECATED(Severity.WARNING);

    private final Severity severity;

    ChangeKind(Severity severity) {
        this.severity = severity;
    }

    public Severity severity() {
        return severity;
    }
}
