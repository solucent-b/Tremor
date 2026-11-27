package dev.tremor.bytecode;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * A class, interface, enum, record or annotation as declared in a class file.
 *
 * @param name internal name, e.g. {@code com/google/common/base/Strings}
 * @param access effective access flags; for nested classes, taken from the {@code InnerClasses} entry
 * @param superName internal name of the superclass, or null for {@code java/lang/Object} itself
 * @param outerName internal name of the enclosing class for member classes, else null
 * @param local true for anonymous and local classes, which are never API
 * @param methods methods keyed by {@code name + descriptor}
 * @param fields fields keyed by name
 */
public record ClassInfo(
        String name,
        int access,
        String superName,
        List<String> interfaces,
        String outerName,
        boolean local,
        boolean deprecated,
        Map<String, MethodInfo> methods,
        Map<String, FieldInfo> fields) implements Serializable {

    public ClassInfo {
        interfaces = List.copyOf(interfaces);
        methods = Map.copyOf(methods);
        fields = Map.copyOf(fields);
    }

    public boolean isInterface() {
        return Access.isInterface(access);
    }

    public boolean isFinal() {
        return Access.isFinal(access);
    }

    public boolean isAbstract() {
        return Access.isAbstract(access);
    }

    /** Dotted, human-readable name, e.g. {@code com.google.common.base.Strings}. */
    public String displayName() {
        return Names.display(name);
    }

    public String packageName() {
        int slash = name.lastIndexOf('/');
        return slash < 0 ? "" : name.substring(0, slash);
    }
}
