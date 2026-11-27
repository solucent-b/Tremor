package dev.tremor.bytecode;

/**
 * Result of looking up a member through a class hierarchy.
 *
 * <p>Three outcomes: found ({@code value != null}), definitely missing, or unknown because the
 * hierarchy leads to a class Tremor cannot see (e.g. one from another library).
 */
public record Lookup<T>(T value, boolean unknown) {

    public static <T> Lookup<T> found(T value) {
        return new Lookup<>(value, false);
    }

    public static <T> Lookup<T> missing() {
        return new Lookup<>(null, false);
    }

    public static <T> Lookup<T> unknownHierarchy() {
        return new Lookup<>(null, true);
    }

    public boolean isFound() {
        return value != null;
    }

    public boolean isMissing() {
        return value == null && !unknown;
    }
}
