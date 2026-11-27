package dev.tremor.bytecode;

import java.io.Serializable;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every class in a jar (or classes directory), with lookups that follow JVM resolution rules.
 * Classes outside the model are looked up in the running JDK; anything else is "unknown".
 */
public final class ApiModel implements Serializable {

    private static final long serialVersionUID = 1L;

    private final Map<String, ClassInfo> classes;
    private final List<String> problems;

    public ApiModel(Map<String, ClassInfo> classes, List<String> problems) {
        this.classes = Map.copyOf(classes);
        this.problems = List.copyOf(problems);
    }

    /** All classes in this model, keyed by internal name. */
    public Map<String, ClassInfo> classes() {
        return classes;
    }

    /** Classes that could not be read and were skipped. */
    public List<String> problems() {
        return problems;
    }

    public boolean contains(String internalName) {
        return classes.containsKey(internalName);
    }

    /** A class from this model, or null. */
    public ClassInfo get(String internalName) {
        return classes.get(internalName);
    }

    /** A class from this model or the JDK, or null if unknown. */
    public ClassInfo lookup(String internalName) {
        ClassInfo c = classes.get(internalName);
        return c != null ? c : JdkClasses.get(internalName);
    }

    /** True if the class is part of the public API: public (or protected nested) all the way out. */
    public boolean isApiClass(String internalName) {
        ClassInfo c = classes.get(internalName);
        if (c == null || c.local() || Access.isSynthetic(c.access()) || !Access.isApi(c.access())) {
            return false;
        }
        return c.outerName() == null || !classes.containsKey(c.outerName()) || isApiClass(c.outerName());
    }

    /** All supertypes (classes and interfaces, transitively), including ones outside the model. */
    public Set<String> supertypes(String internalName) {
        Set<String> out = new LinkedHashSet<>();
        Deque<String> todo = new ArrayDeque<>();
        todo.add(internalName);
        while (!todo.isEmpty()) {
            ClassInfo c = lookup(todo.poll());
            if (c == null) {
                continue;
            }
            if (c.superName() != null && out.add(c.superName())) {
                todo.add(c.superName());
            }
            for (String i : c.interfaces()) {
                if (out.add(i)) {
                    todo.add(i);
                }
            }
        }
        return out;
    }

    /** True if every class in the hierarchy of {@code internalName} is known (model or JDK). */
    public boolean hierarchyFullyKnown(String internalName) {
        if (lookup(internalName) == null) {
            return false;
        }
        for (String s : supertypes(internalName)) {
            if (lookup(s) == null) {
                return false;
            }
        }
        return true;
    }

    /** Resolves a method reference the way the JVM does (JVMS 5.4.3.3 and 5.4.3.4, simplified). */
    public Lookup<ResolvedMethod> resolveMethod(String owner, String name, String descriptor) {
        String key = name + descriptor;
        boolean unknown = false;
        // 1. The class and its superclasses.
        String current = owner;
        Set<String> seen = new HashSet<>();
        while (current != null && seen.add(current)) {
            ClassInfo c = lookup(current);
            if (c == null) {
                unknown = true;
                break;
            }
            MethodInfo m = c.methods().get(key);
            if (m != null) {
                return Lookup.found(new ResolvedMethod(c, m));
            }
            if (name.equals("<init>")) {
                return Lookup.missing(); // constructors are never inherited
            }
            current = c.superName();
        }
        // 2. Superinterfaces (default and abstract methods).
        for (String s : supertypes(owner)) {
            ClassInfo c = lookup(s);
            if (c == null) {
                unknown = true;
                continue;
            }
            if (c.isInterface()) {
                MethodInfo m = c.methods().get(key);
                if (m != null && !Access.isPrivate(m.access()) && !m.isStatic()) {
                    return Lookup.found(new ResolvedMethod(c, m));
                }
            }
        }
        // 3. Interfaces implicitly get Object's public methods.
        ClassInfo ownerInfo = lookup(owner);
        if (ownerInfo != null && ownerInfo.isInterface()) {
            ClassInfo object = lookup("java/lang/Object");
            if (object != null && object.methods().containsKey(key)) {
                return Lookup.found(new ResolvedMethod(object, object.methods().get(key)));
            }
        }
        return unknown ? Lookup.unknownHierarchy() : Lookup.missing();
    }

    /** Resolves a field reference: the class, then its superinterfaces, then its superclass. */
    public Lookup<ResolvedField> resolveField(String owner, String name) {
        return resolveField(owner, name, new HashSet<>());
    }

    private Lookup<ResolvedField> resolveField(String owner, String name, Set<String> seen) {
        if (owner == null || !seen.add(owner)) {
            return Lookup.missing();
        }
        ClassInfo c = lookup(owner);
        if (c == null) {
            return Lookup.unknownHierarchy();
        }
        FieldInfo f = c.fields().get(name);
        if (f != null) {
            return Lookup.found(new ResolvedField(c, f));
        }
        boolean unknown = false;
        for (String i : c.interfaces()) {
            Lookup<ResolvedField> r = resolveField(i, name, seen);
            if (r.isFound()) {
                return r;
            }
            unknown |= r.unknown();
        }
        Lookup<ResolvedField> r = resolveField(c.superName(), name, seen);
        if (r.isFound()) {
            return r;
        }
        return unknown || r.unknown() ? Lookup.unknownHierarchy() : Lookup.missing();
    }

    /**
     * Every API method callable on a class, declared or inherited, keyed by {@code name + descriptor}.
     * The nearest declaration wins. Constructors are only taken from the class itself.
     */
    public Map<String, ResolvedMethod> visibleApiMethods(String internalName) {
        Map<String, ResolvedMethod> out = new LinkedHashMap<>();
        ClassInfo self = lookup(internalName);
        if (self == null) {
            return out;
        }
        List<String> order = new java.util.ArrayList<>();
        order.add(internalName);
        order.addAll(supertypes(internalName));
        for (String name : order) {
            ClassInfo c = lookup(name);
            if (c == null) {
                continue;
            }
            for (MethodInfo m : c.methods().values()) {
                if (!m.isApi() || m.isStaticInitializer()) {
                    continue;
                }
                if (m.isConstructor() && c != self) {
                    continue;
                }
                if (m.isStatic() && c.isInterface() && c != self) {
                    continue; // static interface methods are not inherited
                }
                out.putIfAbsent(m.key(), new ResolvedMethod(c, m));
            }
        }
        return out;
    }
}
