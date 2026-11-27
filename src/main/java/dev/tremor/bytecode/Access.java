package dev.tremor.bytecode;

import org.objectweb.asm.Opcodes;

/** Helpers for JVM access flags. */
public final class Access {

    private Access() {
    }

    public static boolean isPublic(int access) {
        return (access & Opcodes.ACC_PUBLIC) != 0;
    }

    public static boolean isProtected(int access) {
        return (access & Opcodes.ACC_PROTECTED) != 0;
    }

    public static boolean isPrivate(int access) {
        return (access & Opcodes.ACC_PRIVATE) != 0;
    }

    public static boolean isStatic(int access) {
        return (access & Opcodes.ACC_STATIC) != 0;
    }

    public static boolean isFinal(int access) {
        return (access & Opcodes.ACC_FINAL) != 0;
    }

    public static boolean isAbstract(int access) {
        return (access & Opcodes.ACC_ABSTRACT) != 0;
    }

    public static boolean isInterface(int access) {
        return (access & Opcodes.ACC_INTERFACE) != 0;
    }

    public static boolean isSynthetic(int access) {
        return (access & Opcodes.ACC_SYNTHETIC) != 0;
    }

    public static boolean isBridge(int access) {
        return (access & Opcodes.ACC_BRIDGE) != 0;
    }

    public static boolean isDeprecated(int access) {
        return (access & Opcodes.ACC_DEPRECATED) != 0;
    }

    /** Public or protected: visible outside the library. */
    public static boolean isApi(int access) {
        return isPublic(access) || isProtected(access);
    }

    /** Visibility rank: private 0, package-private 1, protected 2, public 3. */
    public static int visibilityRank(int access) {
        if (isPublic(access)) {
            return 3;
        }
        if (isProtected(access)) {
            return 2;
        }
        return isPrivate(access) ? 0 : 1;
    }

    public static String visibilityName(int access) {
        return switch (visibilityRank(access)) {
            case 3 -> "public";
            case 2 -> "protected";
            case 1 -> "package-private";
            default -> "private";
        };
    }
}
