package dev.tremor.testing;

import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/** Compiles Java sources in tests, with full debug info (line numbers and source file names). */
public final class JavaCompilation {

    private JavaCompilation() {
    }

    /**
     * Compiles sources into {@code outDir}.
     *
     * @param sources map of fully qualified class name to source code
     */
    public static Path compile(Path outDir, List<Path> classpath, Map<String, String> sources) {
        try {
            Path srcDir = Files.createTempDirectory("tremor-src");
            List<String> files = new ArrayList<>();
            for (var e : sources.entrySet()) {
                Path file = srcDir.resolve(e.getKey().replace('.', '/') + ".java");
                Files.createDirectories(file.getParent());
                Files.writeString(file, e.getValue());
                files.add(file.toString());
            }
            Files.createDirectories(outDir);
            List<String> args = new ArrayList<>(List.of("-g", "-proc:none", "-nowarn", "-d", outDir.toString()));
            if (!classpath.isEmpty()) {
                args.add("-cp");
                args.add(classpath.stream().map(Path::toString).collect(Collectors.joining(java.io.File.pathSeparator)));
            }
            args.addAll(files);
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            StringWriter errors = new StringWriter();
            int code = compiler.run(null, null, new java.io.PrintStream(new java.io.OutputStream() {
                @Override
                public void write(int b) {
                    errors.write(b);
                }
            }), args.toArray(String[]::new));
            if (code != 0) {
                throw new IllegalStateException("Compilation failed:\n" + errors);
            }
            return outDir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Packs a directory of classes into a jar. */
    public static Path jar(Path classesDir, Path jarFile) {
        try {
            Files.createDirectories(jarFile.getParent());
            try (OutputStream out = Files.newOutputStream(jarFile);
                    JarOutputStream zip = new JarOutputStream(out);
                    Stream<Path> files = Files.walk(classesDir)) {
                for (Path f : files.filter(Files::isRegularFile).sorted().toList()) {
                    zip.putNextEntry(new ZipEntry(classesDir.relativize(f).toString().replace('\\', '/')));
                    zip.write(Files.readAllBytes(f));
                    zip.closeEntry();
                }
            }
            return jarFile;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Compiles straight to a jar. */
    public static Path compileJar(Path workDir, String name, List<Path> classpath, Map<String, String> sources) {
        Path classes = compile(workDir.resolve(name + "-classes"), classpath, sources);
        return jar(classes, workDir.resolve(name + ".jar"));
    }
}
