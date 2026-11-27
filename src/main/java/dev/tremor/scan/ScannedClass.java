package dev.tremor.scan;

import java.io.Serializable;
import java.util.List;

/** A class from the scanned code, with what Tremor needs to check overrides and inheritance. */
public record ScannedClass(
        String name,
        String superName,
        List<String> interfaces,
        String sourceFile,
        int firstLine,
        boolean isInterface,
        boolean isAbstract,
        List<DeclaredMethod> methods,
        List<String> fields) implements Serializable {

    public ScannedClass {
        interfaces = List.copyOf(interfaces);
        methods = List.copyOf(methods);
        fields = List.copyOf(fields);
    }

    public boolean declaresMethod(String name, String descriptor) {
        return methods.stream().anyMatch(m -> m.name().equals(name) && m.descriptor().equals(descriptor));
    }

    public Location location() {
        return new Location(name, sourceFile, firstLine, null);
    }

    /** A method declared by a scanned class. */
    public record DeclaredMethod(String name, String descriptor, int access, int line) implements Serializable {
    }
}
