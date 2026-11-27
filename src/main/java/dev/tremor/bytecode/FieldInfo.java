package dev.tremor.bytecode;

import java.io.Serializable;

/** A field as declared in a class file. */
public record FieldInfo(String name, String descriptor, int access, boolean deprecated) implements Serializable {

    public boolean isApi() {
        return Access.isApi(access) && !Access.isSynthetic(access);
    }

    public boolean isStatic() {
        return Access.isStatic(access);
    }

    public boolean isFinal() {
        return Access.isFinal(access);
    }
}
