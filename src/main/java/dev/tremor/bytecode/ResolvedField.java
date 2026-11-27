package dev.tremor.bytecode;

/** A field found by walking the class hierarchy, with the class that declares it. */
public record ResolvedField(ClassInfo declaringClass, FieldInfo field) {
}
