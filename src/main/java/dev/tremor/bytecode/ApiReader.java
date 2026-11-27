package dev.tremor.bytecode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Reads class files into an {@link ApiModel}.
 *
 * <p>Multi-release jars are read from their base entries only; {@code module-info} and
 * {@code package-info} are skipped. A class that cannot be parsed is recorded as a problem and
 * skipped so one odd file never stops the analysis.
 */
public final class ApiReader {

    private static final String DEPRECATED = "Ljava/lang/Deprecated;";

    private ApiReader() {
    }

    /** Reads a jar file or a directory of class files. */
    public static ApiModel read(Path jarOrDirectory) {
        return Files.isDirectory(jarOrDirectory) ? readDirectory(jarOrDirectory) : readJar(jarOrDirectory);
    }

    public static ApiModel readJar(Path jar) {
        Map<String, ClassInfo> classes = new HashMap<>();
        List<String> problems = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!isRegularClass(entry.getName())) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    add(classes, problems, entry.getName(), in.readAllBytes());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + jar + ": " + e.getMessage(), e);
        }
        return new ApiModel(classes, problems);
    }

    public static ApiModel readDirectory(Path dir) {
        Map<String, ClassInfo> classes = new HashMap<>();
        List<String> problems = new ArrayList<>();
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String rel = dir.relativize(file).toString().replace('\\', '/');
                if (isRegularClass(rel)) {
                    add(classes, problems, rel, Files.readAllBytes(file));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + dir + ": " + e.getMessage(), e);
        }
        return new ApiModel(classes, problems);
    }

    static boolean isRegularClass(String path) {
        return path.endsWith(".class")
                && !path.startsWith("META-INF/")
                && !path.endsWith("module-info.class")
                && !path.endsWith("package-info.class");
    }

    private static void add(Map<String, ClassInfo> classes, List<String> problems, String path, byte[] bytes) {
        try {
            ClassInfo info = readClass(bytes);
            classes.put(info.name(), info);
        } catch (RuntimeException e) {
            problems.add(path + ": " + e);
        }
    }

    /** Reads a single class file. */
    public static ClassInfo readClass(byte[] bytes) {
        Collector collector = new Collector();
        new ClassReader(bytes).accept(collector, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return collector.build();
    }

    private static final class Collector extends ClassVisitor {
        private String name;
        private int access;
        private String superName;
        private List<String> interfaces = List.of();
        private String outerName;
        private boolean local;
        private boolean deprecated;
        private final Map<String, MethodInfo> methods = new LinkedHashMap<>();
        private final Map<String, FieldInfo> fields = new LinkedHashMap<>();

        Collector() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName,
                String[] interfaces) {
            this.name = name;
            this.access = access;
            this.superName = superName;
            this.interfaces = interfaces == null ? List.of() : List.of(interfaces);
            this.deprecated = Access.isDeprecated(access);
        }

        @Override
        public void visitInnerClass(String innerName, String outer, String simpleName, int innerAccess) {
            if (innerName.equals(name)) {
                // The real access of a nested class lives here, not in the class header.
                access = (access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE
                        | Opcodes.ACC_STATIC))
                        | (innerAccess & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE
                        | Opcodes.ACC_STATIC));
                outerName = outer;
                local = outer == null || simpleName == null;
            }
        }

        @Override
        public void visitOuterClass(String owner, String method, String descriptor) {
            local = true; // only local and anonymous classes have an EnclosingMethod attribute
            if (outerName == null) {
                outerName = owner;
            }
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            if (DEPRECATED.equals(descriptor)) {
                deprecated = true;
            }
            return null;
        }

        @Override
        public FieldVisitor visitField(int fAccess, String fName, String descriptor, String signature, Object value) {
            boolean[] dep = {Access.isDeprecated(fAccess)};
            fields.put(fName, new FieldInfo(fName, descriptor, fAccess, dep[0]));
            return new FieldVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                    if (DEPRECATED.equals(desc)) {
                        fields.put(fName, new FieldInfo(fName, descriptor, fAccess, true));
                    }
                    return null;
                }
            };
        }

        @Override
        public MethodVisitor visitMethod(int mAccess, String mName, String descriptor, String signature,
                String[] exceptions) {
            List<String> ex = exceptions == null ? List.of() : List.of(exceptions);
            MethodInfo info = new MethodInfo(mName, descriptor, mAccess, ex, Access.isDeprecated(mAccess));
            methods.put(info.key(), info);
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                    if (DEPRECATED.equals(desc)) {
                        methods.put(info.key(), new MethodInfo(mName, descriptor, mAccess, ex, true));
                    }
                    return null;
                }
            };
        }

        ClassInfo build() {
            return new ClassInfo(name, access, superName, interfaces, outerName, local, deprecated, methods, fields);
        }
    }
}
