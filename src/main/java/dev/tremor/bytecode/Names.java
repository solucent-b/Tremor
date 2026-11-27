package dev.tremor.bytecode;

import java.util.Arrays;
import java.util.stream.Collectors;
import org.objectweb.asm.Type;

/** Turns JVM internal names and descriptors into readable Java. */
public final class Names {

    private Names() {
    }

    /** {@code com/foo/Bar$Baz} to {@code com.foo.Bar.Baz}. */
    public static String display(String internalName) {
        return internalName.replace('/', '.').replace('$', '.');
    }

    /** Last segment, e.g. {@code Bar.Baz}. */
    public static String simple(String internalName) {
        String s = internalName.substring(internalName.lastIndexOf('/') + 1);
        return s.replace('$', '.');
    }

    /** A readable type for a field descriptor, using simple names: {@code List}, {@code int[]}. */
    public static String type(Type type) {
        return switch (type.getSort()) {
            case Type.ARRAY -> type(type.getElementType()) + "[]".repeat(type.getDimensions());
            case Type.OBJECT -> simple(type.getInternalName());
            default -> type.getClassName();
        };
    }

    public static String fieldType(String descriptor) {
        return type(Type.getType(descriptor));
    }

    /** {@code (Ljava/lang/Object;I)V} to {@code (Object, int)}. */
    public static String parameters(String methodDescriptor) {
        return Arrays.stream(Type.getArgumentTypes(methodDescriptor))
                .map(Names::type)
                .collect(Collectors.joining(", ", "(", ")"));
    }

    public static String returnType(String methodDescriptor) {
        return type(Type.getReturnType(methodDescriptor));
    }

    /** {@code Cache.getIfPresent(Object)}, or {@code new Cache(int)} for constructors. */
    public static String method(String owner, String name, String descriptor) {
        if (name.equals("<init>")) {
            return "new " + simple(owner) + parameters(descriptor);
        }
        return simple(owner) + "." + name + parameters(descriptor);
    }

    public static String field(String owner, String name) {
        return simple(owner) + "." + name;
    }
}
