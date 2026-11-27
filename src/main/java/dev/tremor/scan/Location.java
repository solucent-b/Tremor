package dev.tremor.scan;

import java.io.Serializable;

/**
 * Where a usage occurs in compiled code.
 *
 * @param className internal name of the class containing the usage
 * @param sourceFile the {@code SourceFile} attribute, e.g. {@code UserService.java}, or null
 * @param line line number from the {@code LineNumberTable}, or 0 when unknown
 * @param member the method or field the usage is in, e.g. {@code save}, or null at class level
 */
public record Location(String className, String sourceFile, int line, String member) implements Serializable {

    /** Package path plus source file, e.g. {@code com/example/UserService.java}. */
    public String sourcePath() {
        String pkg = className.contains("/") ? className.substring(0, className.lastIndexOf('/') + 1) : "";
        String file = sourceFile != null ? sourceFile : className.substring(className.lastIndexOf('/') + 1)
                .replaceAll("\\$.*", "") + ".java";
        return pkg + file;
    }

    /** {@code UserService.java:84}, or just the file name when the line is unknown. */
    public String shortForm() {
        String file = sourcePath().substring(sourcePath().lastIndexOf('/') + 1);
        return line > 0 ? file + ":" + line : file;
    }
}
