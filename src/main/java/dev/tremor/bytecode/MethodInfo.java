package dev.tremor.bytecode;

import java.io.Serializable;
import java.util.List;

/** A method or constructor as declared in a class file. */
public record MethodInfo(String name, String descriptor, int access, List<String> exceptions, boolean deprecated)
        implements Serializable {

    public MethodInfo {
        exceptions = List.copyOf(exceptions);
    }

    public String key() {
        return name + descriptor;
    }

    public boolean isConstructor() {
        return name.equals("<init>");
    }

    public boolean isStaticInitializer() {
        return name.equals("<clinit>");
    }

    public boolean isApi() {
        return Access.isApi(access) && !Access.isSynthetic(access) && !isStaticInitializer();
    }

    public boolean isStatic() {
        return Access.isStatic(access);
    }

    public boolean isAbstract() {
        return Access.isAbstract(access);
    }

    public boolean isFinal() {
        return Access.isFinal(access);
    }
}
