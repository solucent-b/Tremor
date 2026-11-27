package dev.tremor.bytecode;

/** A method found by walking the class hierarchy, with the class that declares it. */
public record ResolvedMethod(ClassInfo declaringClass, MethodInfo method) {
}
