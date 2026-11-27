package dev.tremor.diff;

/**
 * One change between two versions of a library's API.
 *
 * @param className internal name of the class the change is visible on
 * @param memberName method, constructor ({@code <init>}) or field name; null for class-level changes
 * @param memberDescriptor JVM descriptor of the member in the OLD version; null for class-level changes
 * @param inherited true when the member is declared in a superclass of {@code className}
 * @param subject the class or member that changed, e.g. {@code Cache.getIfPresent(Object)}
 * @param reason what happened to it, e.g. {@code was removed}
 * @param hint what to do instead, or null when Tremor has no suggestion
 */
public record ApiChange(
        ChangeKind kind,
        String className,
        String memberName,
        String memberDescriptor,
        boolean inherited,
        String subject,
        String reason,
        String hint) {

    /** Subject and reason as one sentence, e.g. {@code Cache.getIfPresent(Object) was removed}. */
    public String description() {
        return subject + " " + reason;
    }

    public Severity severity() {
        return kind.severity();
    }

    public boolean isClassLevel() {
        return memberName == null;
    }

    public boolean isField() {
        return memberName != null && memberDescriptor != null && !memberDescriptor.startsWith("(");
    }
}
