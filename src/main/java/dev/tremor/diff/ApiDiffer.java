package dev.tremor.diff;

import dev.tremor.bytecode.Access;
import dev.tremor.bytecode.ApiModel;
import dev.tremor.bytecode.ClassInfo;
import dev.tremor.bytecode.Lookup;
import dev.tremor.bytecode.MethodInfo;
import dev.tremor.bytecode.Names;
import dev.tremor.bytecode.ResolvedField;
import dev.tremor.bytecode.ResolvedMethod;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Compares two {@link ApiModel}s and lists what changed in the public API.
 *
 * <p>Members are compared as they are <em>visible</em> on each class, including inherited ones,
 * so a method that moved up into a superclass is not reported as removed.
 */
public final class ApiDiffer {

    private final ApiModel oldModel;
    private final ApiModel newModel;
    private final List<ApiChange> changes = new ArrayList<>();

    private ApiDiffer(ApiModel oldModel, ApiModel newModel) {
        this.oldModel = oldModel;
        this.newModel = newModel;
    }

    public static ApiDiff diff(ApiModel oldModel, ApiModel newModel) {
        ApiDiffer differ = new ApiDiffer(oldModel, newModel);
        for (String name : new TreeSet<>(oldModel.classes().keySet())) {
            if (oldModel.isApiClass(name)) {
                differ.diffClass(name);
            }
        }
        return new ApiDiff(differ.changes);
    }

    private void diffClass(String name) {
        ClassInfo o = oldModel.get(name);
        ClassInfo n = newModel.get(name);
        String display = Names.simple(name);
        if (n == null) {
            classChange(ChangeKind.REMOVED_CLASS, name, display + " was removed", movedClassHint(name));
            return;
        }
        if (!newModel.isApiClass(name)) {
            classChange(ChangeKind.CLASS_LESS_VISIBLE, name,
                    display + " is no longer public (now " + Access.visibilityName(n.access()) + ")", null);
            return;
        }
        if (o.isInterface() != n.isInterface()) {
            classChange(ChangeKind.CLASS_KIND_CHANGED, name, display + " changed from "
                    + kind(o) + " to " + kind(n), "Recompile against the new version and adjust the code.");
            return;
        }
        if (!o.isFinal() && n.isFinal() && !o.isInterface()) {
            classChange(ChangeKind.CLASS_NOW_FINAL, name, display + " is now final and cannot be extended",
                    "Use composition (wrap an instance) instead of extending it.");
        }
        if (!o.isAbstract() && n.isAbstract() && !n.isInterface()) {
            classChange(ChangeKind.CLASS_NOW_ABSTRACT, name, display + " is now abstract and cannot be created with new",
                    "Look for a factory method or a concrete subclass.");
        }
        if (!o.deprecated() && n.deprecated()) {
            classChange(ChangeKind.DEPRECATED, name, display + " is now deprecated", null);
        }
        diffSupertypes(name);
        diffMethods(name, o, n);
        diffFields(name);
    }

    private void diffSupertypes(String name) {
        if (!newModel.hierarchyFullyKnown(name)) {
            return;
        }
        Set<String> after = newModel.supertypes(name);
        List<String> removed = oldModel.supertypes(name).stream()
                .filter(s -> !after.contains(s) && !s.equals("java/lang/Object")).toList();
        for (String s : removed) {
            // Only report the topmost lost type; its own supertypes are implied.
            boolean implied = removed.stream().anyMatch(r -> !r.equals(s) && oldModel.supertypes(r).contains(s));
            if (!implied) {
                classChange(ChangeKind.REMOVED_SUPERTYPE, name,
                        Names.simple(name) + " no longer extends or implements " + Names.simple(s), null);
            }
        }
    }

    private void diffMethods(String name, ClassInfo o, ClassInfo n) {
        Map<String, ResolvedMethod> before = oldModel.visibleApiMethods(name);
        Map<String, ResolvedMethod> after = newModel.visibleApiMethods(name);
        for (var entry : before.entrySet()) {
            ResolvedMethod rm = entry.getValue();
            if (!oldModel.contains(rm.declaringClass().name())) {
                continue; // inherited from the JDK; unchanged by this upgrade
            }
            MethodInfo om = rm.method();
            boolean inherited = !rm.declaringClass().name().equals(name);
            ResolvedMethod nm = after.get(entry.getKey());
            if (nm == null) {
                methodGone(name, om, inherited, after.values());
            } else {
                methodChanged(name, o, n, om, nm.method(), inherited);
            }
        }
        if (n.isInterface() || n.isAbstract()) {
            for (var entry : after.entrySet()) {
                MethodInfo nm = entry.getValue().method();
                if (!before.containsKey(entry.getKey()) && nm.isAbstract() && !nm.isStatic()
                        && newModel.contains(entry.getValue().declaringClass().name())) {
                    changes.add(new ApiChange(ChangeKind.ABSTRACT_METHOD_ADDED, name, nm.name(), nm.descriptor(),
                            !entry.getValue().declaringClass().name().equals(name),
                            "New abstract method " + Names.method(name, nm.name(), nm.descriptor())
                                    + " must be implemented by every subclass or implementation",
                            "Implement " + nm.name() + Names.parameters(nm.descriptor()) + " in your class."));
                }
            }
        }
    }

    private void methodGone(String name, MethodInfo om, boolean inherited, Collection<ResolvedMethod> after) {
        String what = Names.method(name, om.name(), om.descriptor());
        Lookup<ResolvedMethod> hidden = newModel.resolveMethod(name, om.name(), om.descriptor());
        if (hidden.isFound()) {
            member(ChangeKind.METHOD_LESS_VISIBLE, name, om, inherited, what + " is no longer accessible (now "
                    + Access.visibilityName(hidden.value().method().access()) + ")", null);
            return;
        }
        if (hidden.unknown() && !om.isConstructor()) {
            return; // it may now come from a superclass Tremor cannot see
        }
        List<MethodInfo> sameName = after.stream().map(ResolvedMethod::method)
                .filter(m -> m.name().equals(om.name()) && m.isStatic() == om.isStatic())
                .toList();
        String params = om.descriptor().substring(0, om.descriptor().indexOf(')') + 1);
        MethodInfo sameParams = sameName.stream().filter(m -> m.descriptor().startsWith(params)).findFirst().orElse(null);
        if (sameParams != null) {
            member(ChangeKind.RETURN_TYPE_CHANGED, name, om, inherited, what + " now returns "
                    + Names.returnType(sameParams.descriptor()) + " instead of " + Names.returnType(om.descriptor()),
                    "Recompile against the new version; code that uses the result may need changes.");
        } else if (!sameName.isEmpty()) {
            member(ChangeKind.PARAMETERS_CHANGED, name, om, inherited, what + " no longer exists with these parameters",
                    "Use " + describeAll(name, sameName) + " instead.");
        } else {
            ChangeKind kind = om.isConstructor() ? ChangeKind.REMOVED_CONSTRUCTOR : ChangeKind.REMOVED_METHOD;
            List<MethodInfo> similar = after.stream().map(ResolvedMethod::method)
                    .filter(m -> !m.isConstructor() && similarName(m.name(), om.name()))
                    .toList();
            String hint = similar.isEmpty() ? null : "Maybe use " + describeAll(name, similar) + " instead.";
            member(kind, name, om, inherited, what + " was removed", hint);
        }
    }

    private void methodChanged(String name, ClassInfo o, ClassInfo n, MethodInfo om, MethodInfo nm, boolean inherited) {
        String what = Names.method(name, om.name(), om.descriptor());
        if (Access.visibilityRank(nm.access()) < Access.visibilityRank(om.access())) {
            member(ChangeKind.METHOD_LESS_VISIBLE, name, om, inherited, what + " is now "
                    + Access.visibilityName(nm.access()) + " (was " + Access.visibilityName(om.access()) + ")", null);
        }
        if (om.isStatic() != nm.isStatic()) {
            member(ChangeKind.METHOD_STATIC_CHANGED, name, om, inherited, what + " is now "
                    + (nm.isStatic() ? "static" : "an instance method") + " (was "
                    + (om.isStatic() ? "static" : "an instance method") + ")",
                    nm.isStatic() ? "Call it as " + Names.simple(name) + "." + om.name() + "(...)."
                            : "Call it on an instance instead of the class.");
        }
        if (!om.isFinal() && nm.isFinal() && !o.isFinal() && !n.isFinal() && !om.isStatic()) {
            member(ChangeKind.METHOD_NOW_FINAL, name, om, inherited, what + " is now final and cannot be overridden",
                    null);
        }
        if (!om.isAbstract() && nm.isAbstract()) {
            member(ChangeKind.METHOD_NOW_ABSTRACT, name, om, inherited, what + " is now abstract",
                    "Subclasses must implement " + om.name() + Names.parameters(om.descriptor()) + ".");
        }
        List<String> added = nm.exceptions().stream()
                .filter(e -> !om.exceptions().contains(e) && isChecked(e)).toList();
        if (!added.isEmpty()) {
            member(ChangeKind.NEW_CHECKED_EXCEPTION, name, om, inherited, what + " now throws "
                    + String.join(", ", added.stream().map(Names::simple).toList()),
                    "Callers must catch or declare it when recompiling.");
        }
        if (!om.deprecated() && nm.deprecated()) {
            member(ChangeKind.DEPRECATED, name, om, inherited, what + " is now deprecated", null);
        }
    }

    private void diffFields(String name) {
        Map<String, ResolvedField> before = oldModel.visibleApiFields(name);
        Map<String, ResolvedField> after = newModel.visibleApiFields(name);
        for (ResolvedField rf : before.values()) {
            if (!oldModel.contains(rf.declaringClass().name())) {
                continue;
            }
            var of = rf.field();
            boolean inherited = !rf.declaringClass().name().equals(name);
            String what = Names.field(name, of.name());
            ResolvedField nf = after.get(of.name());
            if (nf == null) {
                Lookup<ResolvedField> hidden = newModel.resolveField(name, of.name());
                if (hidden.isFound()) {
                    field(ChangeKind.FIELD_LESS_VISIBLE, name, of, inherited, what + " is no longer accessible", null);
                } else if (!hidden.unknown()) {
                    List<String> similar = after.keySet().stream().filter(f -> similarName(f, of.name())).toList();
                    field(ChangeKind.REMOVED_FIELD, name, of, inherited, what + " was removed",
                            similar.isEmpty() ? null : "Maybe use " + String.join(" or ",
                                    similar.stream().map(f -> Names.field(name, f)).toList()) + " instead.");
                }
                continue;
            }
            var nfi = nf.field();
            if (!nfi.descriptor().equals(of.descriptor())) {
                field(ChangeKind.FIELD_TYPE_CHANGED, name, of, inherited, what + " changed type from "
                        + Names.fieldType(of.descriptor()) + " to " + Names.fieldType(nfi.descriptor()), null);
            }
            if (of.isStatic() != nfi.isStatic()) {
                field(ChangeKind.FIELD_STATIC_CHANGED, name, of, inherited, what + " is now "
                        + (nfi.isStatic() ? "static" : "an instance field"), null);
            }
            if (!of.isFinal() && nfi.isFinal()) {
                field(ChangeKind.FIELD_NOW_FINAL, name, of, inherited, what + " is now final and cannot be assigned",
                        null);
            }
            if (Access.visibilityRank(nfi.access()) < Access.visibilityRank(of.access())) {
                field(ChangeKind.FIELD_LESS_VISIBLE, name, of, inherited, what + " is now "
                        + Access.visibilityName(nfi.access()), null);
            }
            if (!of.deprecated() && nfi.deprecated()) {
                field(ChangeKind.DEPRECATED, name, of, inherited, what + " is now deprecated", null);
            }
        }
    }

    private boolean isChecked(String exception) {
        if (newModel.isSubtypeOf(exception, "java/lang/RuntimeException")
                || newModel.isSubtypeOf(exception, "java/lang/Error")) {
            return false;
        }
        return true; // unknown hierarchies are treated as checked; this is only ever a warning
    }

    private String movedClassHint(String name) {
        String simple = name.substring(name.lastIndexOf('/') + 1);
        List<String> candidates = newModel.classes().keySet().stream()
                .filter(c -> c.endsWith("/" + simple) && newModel.isApiClass(c))
                .sorted().limit(3).map(Names::display).toList();
        return candidates.isEmpty() ? null : "It may have moved: try " + String.join(" or ", candidates) + ".";
    }

    private static String describeAll(String owner, List<MethodInfo> methods) {
        return String.join(" or ", methods.stream().limit(3)
                .map(m -> Names.method(owner, m.name(), m.descriptor())).toList());
    }

    static boolean similarName(String a, String b) {
        if (a.equals(b)) {
            return false;
        }
        String x = a.toLowerCase(Locale.ROOT);
        String y = b.toLowerCase(Locale.ROOT);
        if (x.length() >= 4 && y.length() >= 4 && (x.contains(y) || y.contains(x))) {
            return true;
        }
        return distance(x, y) <= Math.max(1, Math.min(x.length(), y.length()) / 4);
    }

    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    private static String kind(ClassInfo c) {
        return c.isInterface() ? "an interface" : "a class";
    }

    private void classChange(ChangeKind kind, String name, String description, String hint) {
        changes.add(new ApiChange(kind, name, null, null, false, description, hint));
    }

    private void member(ChangeKind kind, String name, MethodInfo m, boolean inherited, String description, String hint) {
        changes.add(new ApiChange(kind, name, m.name(), m.descriptor(), inherited, description, hint));
    }

    private void field(ChangeKind kind, String name, dev.tremor.bytecode.FieldInfo f, boolean inherited,
            String description, String hint) {
        changes.add(new ApiChange(kind, name, f.name(), f.descriptor(), inherited, description, hint));
    }
}
