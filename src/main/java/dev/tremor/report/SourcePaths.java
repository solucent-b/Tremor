package dev.tremor.report;

import dev.tremor.analysis.Finding;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Turns a finding's location into the path a user would open. */
public final class SourcePaths {

    private static final List<String> SOURCE_ROOTS = List.of(
            "src/main/java", "src/test/java", "src/main/kotlin", "src/test/kotlin",
            "src/main/scala", "src/test/scala", "src/main/groovy", "src/test/groovy");

    private SourcePaths() {
    }

    /**
     * For project code, the real source file relative to the project (e.g.
     * {@code src/main/java/com/example/UserService.java}) when it exists; otherwise the package path.
     * For library code, the package path inside the jar.
     */
    public static String file(Path projectDir, Finding f) {
        String packagePath = f.location().sourcePath();
        if (f.origin().isProject() && projectDir != null) {
            for (String root : SOURCE_ROOTS) {
                if (Files.isRegularFile(projectDir.resolve(root).resolve(packagePath))) {
                    return root + "/" + packagePath;
                }
            }
        }
        return packagePath;
    }

    /** {@link #file} plus {@code :line} when the line is known. */
    public static String fileAndLine(Path projectDir, Finding f) {
        int line = f.location().line();
        return file(projectDir, f) + (line > 0 ? ":" + line : "");
    }
}
