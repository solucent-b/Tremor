package dev.tremor.scan;

import java.io.Serializable;
import org.objectweb.asm.Opcodes;

/**
 * One reference from scanned code to a class or member.
 *
 * @param owner internal name of the referenced class (the class named in the bytecode)
 * @param name member name, or null for class-level usages
 * @param descriptor member descriptor, or null for class-level usages
 * @param opcode the instruction (e.g. {@link Opcodes#INVOKESTATIC}), or for method handles the
 *     handle tag; 0 when not applicable
 */
public record Usage(UsageKind kind, String owner, String name, String descriptor, int opcode, Location location)
        implements Serializable {

    public boolean isStaticAccess() {
        return opcode == Opcodes.INVOKESTATIC || opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC
                || opcode == Opcodes.H_INVOKESTATIC || opcode == Opcodes.H_GETSTATIC || opcode == Opcodes.H_PUTSTATIC;
    }

    public boolean isInterfaceCall() {
        return opcode == Opcodes.INVOKEINTERFACE || opcode == Opcodes.H_INVOKEINTERFACE;
    }

    public boolean isField() {
        return kind == UsageKind.FIELD_READ || kind == UsageKind.FIELD_WRITE;
    }
}
