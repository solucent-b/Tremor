package dev.tremor.bytecode;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Reads JDK classes (from the running JVM) on demand so hierarchies through the JDK resolve. */
final class JdkClasses {

    private static final Map<String, Optional<ClassInfo>> CACHE = new ConcurrentHashMap<>();

    private JdkClasses() {
    }

    static boolean isJdk(String internalName) {
        return internalName.startsWith("java/") || internalName.startsWith("javax/")
                || internalName.startsWith("jdk/") || internalName.startsWith("sun/");
    }

    static ClassInfo get(String internalName) {
        if (!isJdk(internalName)) {
            return null;
        }
        return CACHE.computeIfAbsent(internalName, JdkClasses::load).orElse(null);
    }

    private static Optional<ClassInfo> load(String internalName) {
        // The platform loader sees only the JDK, never libraries on Tremor's own classpath.
        try (InputStream in = ClassLoader.getPlatformClassLoader().getResourceAsStream(internalName + ".class")) {
            if (in == null) {
                return Optional.empty();
            }
            return Optional.of(ApiReader.readClass(in.readAllBytes()));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }
}
