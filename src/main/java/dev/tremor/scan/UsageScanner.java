package dev.tremor.scan;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Finds every reference that compiled code makes to a set of classes: calls, field access,
 * {@code new}, extends/implements, casts, annotations, class literals, catch blocks and method
 * references inside {@code invokedynamic} (lambdas). Records source file and line numbers.
 */
public final class UsageScanner {

    private final Predicate<String> interesting;

    /**
     * @param interesting true for internal class names whose usages should be recorded
     */
    public UsageScanner(Predicate<String> interesting) {
        this.interesting = interesting;
    }

    /** Scans a jar, a classes directory, or several of either. */
    public ScanResult scan(Path... roots) {
        return scan(List.of(roots));
    }

    public ScanResult scan(List<Path> roots) {
        List<Usage> raw = new ArrayList<>();
        Map<String, ScannedClass> classes = new HashMap<>();
        List<String> problems = new ArrayList<>();
        for (Path root : roots) {
            if (Files.isDirectory(root)) {
                scanDirectory(root, raw, classes, problems);
            } else {
                scanJar(root, raw, classes, problems);
            }
        }
        Set<String> bridges = localClassesReachingInteresting(classes);
        List<Usage> kept = raw.stream()
                .filter(u -> interesting.test(u.owner()) || bridges.contains(u.owner()))
                .toList();
        return new ScanResult(kept, classes, problems);
    }

    /** Local classes that inherit from an interesting class, so calls on them may land in it. */
    private Set<String> localClassesReachingInteresting(Map<String, ScannedClass> classes) {
        Set<String> out = new HashSet<>();
        for (String name : classes.keySet()) {
            if (reaches(name, classes, new HashSet<>())) {
                out.add(name);
            }
        }
        return out;
    }

    private boolean reaches(String name, Map<String, ScannedClass> classes, Set<String> seen) {
        if (!seen.add(name)) {
            return false;
        }
        ScannedClass c = classes.get(name);
        if (c == null) {
            return false;
        }
        List<String> supers = new ArrayList<>(c.interfaces());
        if (c.superName() != null) {
            supers.add(c.superName());
        }
        for (String s : supers) {
            if (interesting.test(s) || reaches(s, classes, seen)) {
                return true;
            }
        }
        return false;
    }

    private void scanDirectory(Path dir, List<Usage> usages, Map<String, ScannedClass> classes, List<String> problems) {
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                String rel = dir.relativize(f).toString().replace('\\', '/');
                if (isScannable(rel)) {
                    scanClass(rel, Files.readAllBytes(f), usages, classes, problems);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + dir + ": " + e.getMessage(), e);
        }
    }

    private void scanJar(Path jar, List<Usage> usages, Map<String, ScannedClass> classes, List<String> problems) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (isScannable(e.getName())) {
                    try (InputStream in = zip.getInputStream(e)) {
                        scanClass(e.getName(), in.readAllBytes(), usages, classes, problems);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + jar + ": " + e.getMessage(), e);
        }
    }

    private static boolean isScannable(String path) {
        return path.endsWith(".class") && !path.startsWith("META-INF/") && !path.endsWith("module-info.class");
    }

    private void scanClass(String path, byte[] bytes, List<Usage> usages, Map<String, ScannedClass> classes,
            List<String> problems) {
        try {
            ClassScanner scanner = new ClassScanner();
            new ClassReader(bytes).accept(scanner, ClassReader.SKIP_FRAMES);
            usages.addAll(scanner.usages());
            classes.put(scanner.name, scanner.scannedClass());
        } catch (RuntimeException e) {
            problems.add(path + ": " + e);
        }
    }

    /** Visits one class and records every outgoing reference (unfiltered, minus the JDK). */
    private static final class ClassScanner extends ClassVisitor {
        private String name;
        private String superName;
        private List<String> interfaces = List.of();
        private int access;
        private String sourceFile;
        private int firstLine = Integer.MAX_VALUE;
        private final List<Usage> pending = new ArrayList<>();
        private final List<ScannedClass.DeclaredMethod> methods = new ArrayList<>();
        private final List<String> fields = new ArrayList<>();

        ClassScanner() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName,
                String[] interfaces) {
            this.name = name;
            this.access = access;
            this.superName = superName;
            this.interfaces = interfaces == null ? List.of() : List.of(interfaces);
            if (superName != null) {
                classLevel(UsageKind.EXTENDS, superName);
            }
            for (String i : this.interfaces) {
                classLevel(UsageKind.IMPLEMENTS, i);
            }
        }

        @Override
        public void visitSource(String source, String debug) {
            this.sourceFile = source;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            typeUsage(UsageKind.ANNOTATION, descriptor, null, 0);
            return null;
        }

        @Override
        public FieldVisitor visitField(int fAccess, String fName, String descriptor, String signature, Object value) {
            fields.add(fName);
            typeUsage(UsageKind.TYPE_REFERENCE, descriptor, fName, 0);
            return new FieldVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                    typeUsage(UsageKind.ANNOTATION, desc, fName, 0);
                    return null;
                }
            };
        }

        @Override
        public MethodVisitor visitMethod(int mAccess, String mName, String descriptor, String signature,
                String[] exceptions) {
            int index = methods.size();
            methods.add(new ScannedClass.DeclaredMethod(mName, descriptor, mAccess, 0));
            return new MethodScanner(mName, descriptor, mAccess, index);
        }

        @Override
        public void visitEnd() {
            if (firstLine == Integer.MAX_VALUE) {
                firstLine = 0;
            }
        }

        List<Usage> usages() {
            // Class-level usages carry line 0 until the class is fully read; use the first line seen.
            return pending.stream()
                    .map(u -> u.location().line() == -1
                            ? new Usage(u.kind(), u.owner(), u.name(), u.descriptor(), u.opcode(),
                                    new Location(name, sourceFile, firstLine, u.location().member()))
                            : new Usage(u.kind(), u.owner(), u.name(), u.descriptor(), u.opcode(),
                                    new Location(name, sourceFile, u.location().line(), u.location().member())))
                    .toList();
        }

        ScannedClass scannedClass() {
            return new ScannedClass(name, superName, interfaces, sourceFile, firstLine,
                    (access & Opcodes.ACC_INTERFACE) != 0, (access & Opcodes.ACC_ABSTRACT) != 0, methods, fields);
        }

        private void classLevel(UsageKind kind, String owner) {
            add(kind, owner, null, null, 0, -1, null);
        }

        private void typeUsage(UsageKind kind, String descriptor, String member, int line) {
            for (String owner : ownersIn(Type.getType(descriptor))) {
                add(kind, owner, null, null, 0, line == 0 ? -1 : line, member);
            }
        }

        private void add(UsageKind kind, String owner, String mName, String desc, int opcode, int line, String member) {
            String clean = elementOwner(owner);
            if (clean == null || isJdk(clean)) {
                return;
            }
            pending.add(new Usage(kind, clean, mName, desc, opcode, new Location(null, null, line, member)));
        }

        private final class MethodScanner extends MethodVisitor {
            private final String method;
            private final int index;
            private final Map<String, Integer> pendingNew = new HashMap<>();
            private int line;

            MethodScanner(String method, String descriptor, int mAccess, int index) {
                super(Opcodes.ASM9);
                this.method = method;
                this.index = index;
                Type type = Type.getMethodType(descriptor);
                for (Type t : type.getArgumentTypes()) {
                    for (String o : ownersIn(t)) {
                        add(UsageKind.TYPE_REFERENCE, o, null, null, 0, -1, method);
                    }
                }
                for (String o : ownersIn(type.getReturnType())) {
                    add(UsageKind.TYPE_REFERENCE, o, null, null, 0, -1, method);
                }
            }

            @Override
            public void visitLineNumber(int lineNumber, Label start) {
                if (line == 0) {
                    var m = methods.get(index);
                    methods.set(index, new ScannedClass.DeclaredMethod(m.name(), m.descriptor(), m.access(), lineNumber));
                }
                line = lineNumber;
                firstLine = Math.min(firstLine, lineNumber);
            }

            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                typeUsage(UsageKind.ANNOTATION, descriptor, method, line);
                return null;
            }

            @Override
            public AnnotationVisitor visitParameterAnnotation(int parameter, String descriptor, boolean visible) {
                typeUsage(UsageKind.ANNOTATION, descriptor, method, line);
                return null;
            }

            @Override
            public void visitMethodInsn(int opcode, String owner, String mName, String descriptor, boolean itf) {
                UsageKind kind = UsageKind.METHOD_CALL;
                if (mName.equals("<init>") && pendingNew.getOrDefault(owner, 0) > 0) {
                    // Paired with a NEW instruction: a "new X(...)" expression, not a super() call.
                    pendingNew.merge(owner, -1, Integer::sum);
                    kind = UsageKind.NEW_INSTANCE;
                }
                add(kind, owner, mName, descriptor, opcode, line, method);
            }

            @Override
            public void visitFieldInsn(int opcode, String owner, String fName, String descriptor) {
                UsageKind kind = opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC
                        ? UsageKind.FIELD_WRITE : UsageKind.FIELD_READ;
                add(kind, owner, fName, descriptor, opcode, line, method);
            }

            @Override
            public void visitTypeInsn(int opcode, String type) {
                if (opcode == Opcodes.NEW) {
                    pendingNew.merge(type, 1, Integer::sum);
                    return; // reported together with the constructor call
                }
                UsageKind kind = switch (opcode) {
                    case Opcodes.CHECKCAST -> UsageKind.CAST;
                    case Opcodes.INSTANCEOF -> UsageKind.INSTANCEOF;
                    default -> UsageKind.TYPE_REFERENCE; // ANEWARRAY
                };
                add(kind, type, null, null, opcode, line, method);
            }

            @Override
            public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
                typeUsage(UsageKind.TYPE_REFERENCE, descriptor, method, line);
            }

            @Override
            public void visitLdcInsn(Object value) {
                if (value instanceof Type t && t.getSort() != Type.METHOD) {
                    for (String o : ownersIn(t)) {
                        add(UsageKind.CLASS_LITERAL, o, null, null, 0, line, method);
                    }
                } else if (value instanceof Handle h) {
                    handle(h);
                }
            }

            @Override
            public void visitInvokeDynamicInsn(String iName, String descriptor, Handle bsm, Object... args) {
                for (Object arg : args) {
                    if (arg instanceof Handle h) {
                        handle(h);
                    }
                }
            }

            @Override
            public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
                if (type != null) {
                    add(UsageKind.CATCH, type, null, null, 0, -1, method);
                }
            }

            private void handle(Handle h) {
                int tag = h.getTag();
                boolean field = tag <= Opcodes.H_PUTSTATIC;
                UsageKind kind = field
                        ? (tag == Opcodes.H_PUTFIELD || tag == Opcodes.H_PUTSTATIC ? UsageKind.FIELD_WRITE
                                : UsageKind.FIELD_READ)
                        : UsageKind.METHOD_REFERENCE;
                add(kind, h.getOwner(), h.getName(), h.getDesc(), tag, line, method);
            }
        }
    }

    private static List<String> ownersIn(Type type) {
        Type t = type.getSort() == Type.ARRAY ? type.getElementType() : type;
        return t.getSort() == Type.OBJECT ? List.of(t.getInternalName()) : List.of();
    }

    private static String elementOwner(String owner) {
        if (owner == null) {
            return null;
        }
        if (owner.startsWith("[")) {
            Type t = Type.getType(owner).getElementType();
            return t.getSort() == Type.OBJECT ? t.getInternalName() : null;
        }
        return owner;
    }

    private static boolean isJdk(String internalName) {
        return internalName.startsWith("java/") || internalName.startsWith("jdk/") || internalName.startsWith("sun/");
    }
}
