package dev.tremor.analysis;

import dev.tremor.bytecode.Access;
import dev.tremor.bytecode.ApiModel;
import dev.tremor.bytecode.ClassInfo;
import dev.tremor.bytecode.Lookup;
import dev.tremor.bytecode.MethodInfo;
import dev.tremor.bytecode.Names;
import dev.tremor.bytecode.ResolvedField;
import dev.tremor.bytecode.ResolvedMethod;
import dev.tremor.diff.ApiChange;
import dev.tremor.diff.ApiDiff;
import dev.tremor.diff.ChangeKind;
import dev.tremor.diff.Severity;
import dev.tremor.scan.Location;
import dev.tremor.scan.ScanResult;
import dev.tremor.scan.ScannedClass;
import dev.tremor.scan.Usage;
import dev.tremor.scan.UsageKind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks every usage found by the scanner against the new version of the library, the way the
 * JVM would link it, and turns the ones that fail into {@link Finding}s with a plain-words reason.
 */
public final class UsageMatcher {

    private final ApiModel oldModel;
    private final ApiModel newModel;
    private final ApiDiff diff;

    public UsageMatcher(ApiModel oldModel, ApiModel newModel, ApiDiff diff) {
        this.oldModel = oldModel;
        this.newModel = newModel;
        this.diff = diff;
    }

    public List<Finding> match(ScanResult scan, Origin origin) {
        Map<String, Finding> out = new LinkedHashMap<>(); // de-duplicated by place and message
        Context ctx = new Context(scan, origin, out);
        for (Usage u : scan.usages()) {
            try {
                matchUsage(ctx, u);
            } catch (RuntimeException e) {
                // Never let one odd usage stop the analysis.
            }
        }
        for (ScannedClass c : scan.classes().values()) {
            checkInheritance(ctx, c);
        }
        return new ArrayList<>(out.values());
    }

    private record Context(ScanResult scan, Origin origin, Map<String, Finding> out) {
        boolean bundles(String className) {
            return scan.classes().containsKey(className);
        }

        void add(Finding f) {
            out.putIfAbsent(f.location().sourcePath() + ":" + f.location().line() + ":" + f.action() + ":"
                    + f.reason(), f);
        }
    }

    private void matchUsage(Context ctx, Usage u) {
        if (ctx.bundles(u.owner())) {
            // A local class: follow its hierarchy into the library for member usages.
            if (u.kind().isMemberUsage() && !u.name().equals("<init>")) {
                for (String owner : libraryAncestors(ctx, u.owner())) {
                    if (!declaredLocally(ctx, u.owner(), u)) {
                        matchMember(ctx, u, owner);
                    }
                }
            }
            return;
        }
        if (!oldModel.contains(u.owner())) {
            return;
        }
        if (!u.kind().isMemberUsage()) {
            matchClassUsage(ctx, u);
        } else {
            matchMember(ctx, u, u.owner());
        }
    }

    private void matchClassUsage(Context ctx, Usage u) {
        String owner = u.owner();
        String action = u.kind().verb() + " " + Names.simple(owner);
        if (!classStillUsable(ctx, u, owner, action)) {
            return;
        }
        ClassInfo before = oldModel.get(owner);
        ClassInfo after = newModel.get(owner);
        if (u.kind() == UsageKind.EXTENDS) {
            if (after.isFinal() && !before.isFinal()) {
                breakage(ctx, u, ChangeKind.CLASS_NOW_FINAL, action, "is now final",
                        "the class fails to load (VerifyError)", firstHint(diff.forClass(owner)));
            }
            if (after.isInterface() != before.isInterface()) {
                breakage(ctx, u, ChangeKind.CLASS_KIND_CHANGED, action, "is now an interface",
                        "IncompatibleClassChangeError when the class loads", null);
            }
        } else if (u.kind() == UsageKind.IMPLEMENTS && after.isInterface() != before.isInterface()) {
            breakage(ctx, u, ChangeKind.CLASS_KIND_CHANGED, action, "is no longer an interface",
                    "IncompatibleClassChangeError when the class loads", null);
        }
        warnings(ctx, u, action, diff.forClass(owner));
    }

    private boolean classStillUsable(Context ctx, Usage u, String owner, String action) {
        if (!newModel.contains(owner)) {
            ApiChange change = first(diff.forClass(owner), ChangeKind.REMOVED_CLASS);
            breakage(ctx, u, ChangeKind.REMOVED_CLASS, action, "was removed",
                    "NoClassDefFoundError at runtime", change == null ? null : change.hint());
            return false;
        }
        if (!newModel.isApiClass(owner) && oldModel.isApiClass(owner)) {
            breakage(ctx, u, ChangeKind.CLASS_LESS_VISIBLE, action, "is no longer public",
                    "IllegalAccessError at runtime", null);
            return false;
        }
        return true;
    }

    private void matchMember(Context ctx, Usage u, String owner) {
        String target = u.isField() ? Names.field(owner, u.name()) : Names.method(owner, u.name(), u.descriptor());
        String action;
        if (u.kind() == UsageKind.NEW_INSTANCE) {
            action = "creates " + target.substring("new ".length());
        } else if ("<init>".equals(u.name())) {
            action = "calls constructor " + target.substring("new ".length());
        } else {
            action = u.kind().verb() + " " + target;
        }
        if (!classStillUsable(ctx, u, owner, action)) {
            return;
        }
        if (u.isField()) {
            matchField(ctx, u, owner, action);
        } else {
            matchMethod(ctx, u, owner, action);
        }
    }

    private void matchMethod(Context ctx, Usage u, String owner, String action) {
        Lookup<ResolvedMethod> before = oldModel.resolveMethod(owner, u.name(), u.descriptor());
        if (!before.isFound() || !oldModel.contains(before.value().declaringClass().name())) {
            return; // not something this library provided (or inherited from the JDK)
        }
        Lookup<ResolvedMethod> after = newModel.resolveMethod(owner, u.name(), u.descriptor());
        List<ApiChange> changes = changesFor(owner, before.value(), u);
        if (after.isMissing()) {
            ApiChange change = firstBreaking(changes);
            ChangeKind kind = change != null ? change.kind()
                    : u.name().equals("<init>") ? ChangeKind.REMOVED_CONSTRUCTOR : ChangeKind.REMOVED_METHOD;
            String reason = change != null ? change.reason() : "no longer exists";
            breakage(ctx, u, kind, action, reason, "NoSuchMethodError at runtime",
                    change != null ? change.hint() : null);
            return;
        }
        if (!after.isFound()) {
            return; // hierarchy leads outside what Tremor can see
        }
        MethodInfo m = after.value().method();
        if (!accessible(ctx, u, after.value().declaringClass(), m.access())) {
            breakage(ctx, u, ChangeKind.METHOD_LESS_VISIBLE, action, "is now " + Access.visibilityName(m.access()),
                    "IllegalAccessError at runtime", null);
            return;
        }
        boolean staticCall = u.isStaticAccess();
        if (staticCall != m.isStatic()) {
            ApiChange change = first(changes, ChangeKind.METHOD_STATIC_CHANGED);
            breakage(ctx, u, ChangeKind.METHOD_STATIC_CHANGED, action,
                    m.isStatic() ? "is now static" : "is no longer static",
                    "IncompatibleClassChangeError at runtime", change == null ? null : change.hint());
            return;
        }
        ClassInfo ownerAfter = newModel.get(owner);
        if (u.kind() == UsageKind.METHOD_CALL && !staticCall && !u.name().equals("<init>")
                && u.opcode() != org.objectweb.asm.Opcodes.INVOKESPECIAL
                && u.isInterfaceCall() != ownerAfter.isInterface()) {
            breakage(ctx, u, ChangeKind.CLASS_KIND_CHANGED, action, "is now declared on "
                    + (ownerAfter.isInterface() ? "an interface" : "a class"),
                    "IncompatibleClassChangeError at runtime", null);
            return;
        }
        if (u.kind() == UsageKind.NEW_INSTANCE && ownerAfter.isAbstract() && !oldModel.get(owner).isAbstract()) {
            breakage(ctx, u, ChangeKind.CLASS_NOW_ABSTRACT, action, "is now abstract",
                    "InstantiationError at runtime", firstHint(diff.forClass(owner)));
            return;
        }
        warnings(ctx, u, action, changes);
        warnings(ctx, u, action, deprecationsOf(owner));
    }

    private void matchField(Context ctx, Usage u, String owner, String action) {
        Lookup<ResolvedField> before = oldModel.resolveField(owner, u.name());
        if (!before.isFound() || !oldModel.contains(before.value().declaringClass().name())) {
            return;
        }
        Lookup<ResolvedField> after = newModel.resolveField(owner, u.name());
        List<ApiChange> changes = new ArrayList<>(diff.forField(owner, u.name()));
        changes.addAll(diff.forField(before.value().declaringClass().name(), u.name()));
        if (after.isMissing()) {
            ApiChange change = firstBreaking(changes);
            breakage(ctx, u, ChangeKind.REMOVED_FIELD, action, change != null ? change.reason() : "no longer exists",
                    "NoSuchFieldError at runtime", change != null ? change.hint() : null);
            return;
        }
        if (!after.isFound()) {
            return;
        }
        var f = after.value().field();
        if (!f.descriptor().equals(u.descriptor())) {
            breakage(ctx, u, ChangeKind.FIELD_TYPE_CHANGED, action, "changed type from "
                    + Names.fieldType(u.descriptor()) + " to " + Names.fieldType(f.descriptor()),
                    "NoSuchFieldError at runtime", null);
        } else if (!accessible(ctx, u, after.value().declaringClass(), f.access())) {
            breakage(ctx, u, ChangeKind.FIELD_LESS_VISIBLE, action, "is now " + Access.visibilityName(f.access()),
                    "IllegalAccessError at runtime", null);
        } else if (u.isStaticAccess() != f.isStatic()) {
            breakage(ctx, u, ChangeKind.FIELD_STATIC_CHANGED, action,
                    f.isStatic() ? "is now static" : "is no longer static",
                    "IncompatibleClassChangeError at runtime", null);
        } else if (u.kind() == UsageKind.FIELD_WRITE && f.isFinal() && !before.value().field().isFinal()) {
            breakage(ctx, u, ChangeKind.FIELD_NOW_FINAL, action, "is now final",
                    "IllegalAccessError at runtime", null);
        } else {
            warnings(ctx, u, action, changes);
            warnings(ctx, u, action, deprecationsOf(owner));
        }
    }

    /** Overrides of now-final methods, missing implementations of new abstract methods, lost overrides. */
    private void checkInheritance(Context ctx, ScannedClass c) {
        Set<String> ancestors = libraryAncestors(ctx, c.name());
        if (ancestors.isEmpty()) {
            return;
        }
        for (ScannedClass.DeclaredMethod m : c.methods()) {
            if (m.name().startsWith("<") || Access.isStatic(m.access()) || Access.isPrivate(m.access())
                    || Access.isSynthetic(m.access())) {
                continue;
            }
            for (String a : ancestors) {
                Lookup<ResolvedMethod> after = newModel.resolveMethod(a, m.name(), m.descriptor());
                Lookup<ResolvedMethod> before = oldModel.resolveMethod(a, m.name(), m.descriptor());
                Location where = new Location(c.name(), c.sourceFile(), m.line(), m.name());
                String action = "overrides " + Names.method(a, m.name(), m.descriptor());
                if (after.isFound() && after.value().method().isFinal() && before.isFound()
                        && !before.value().method().isFinal() && newModel.contains(after.value().declaringClass().name())) {
                    ctx.add(new Finding(Severity.BREAKING, ChangeKind.METHOD_NOW_FINAL, ctx.origin(), where, action,
                            "is now final", "the class fails to load (VerifyError)",
                            "Stop overriding it; look for another extension point."));
                } else if (before.isFound() && oldModel.contains(before.value().declaringClass().name())
                        && after.isMissing()) {
                    ctx.add(new Finding(Severity.WARNING, ChangeKind.REMOVED_METHOD, ctx.origin(), where, action,
                            "no longer exists, so the library will never call your version",
                            null, "Check the library's release notes for the new way to customise this."));
                }
            }
        }
        if (c.isAbstract() || c.isInterface()) {
            return;
        }
        for (String a : ancestors) {
            for (ResolvedMethod rm : newModel.visibleApiMethods(a).values()) {
                MethodInfo nm = rm.method();
                if (!nm.isAbstract() || nm.isStatic() || implementedLocally(ctx, c, nm)) {
                    continue;
                }
                boolean newlyAbstract = diff.forMethod(a, nm.name(), nm.descriptor()).stream()
                        .anyMatch(ch -> ch.kind() == ChangeKind.ABSTRACT_METHOD_ADDED
                                || ch.kind() == ChangeKind.METHOD_NOW_ABSTRACT);
                if (newlyAbstract) {
                    ctx.add(new Finding(Severity.BREAKING, ChangeKind.ABSTRACT_METHOD_ADDED, ctx.origin(),
                            c.location(), (newModel.get(a) != null && newModel.get(a).isInterface() ? "implements "
                                    : "extends ") + Names.simple(a),
                            "now requires " + Names.method(a, nm.name(), nm.descriptor()) + " to be implemented",
                            "AbstractMethodError when it is called; the code no longer compiles",
                            "Implement " + nm.name() + Names.parameters(nm.descriptor()) + " in "
                                    + Names.simple(c.name()) + "."));
                }
            }
        }
    }

    private boolean implementedLocally(Context ctx, ScannedClass c, MethodInfo m) {
        ScannedClass current = c;
        Set<String> seen = new LinkedHashSet<>();
        while (current != null && seen.add(current.name())) {
            if (current.declaresMethod(m.name(), m.descriptor())) {
                return true;
            }
            current = current.superName() == null ? null : ctx.scan().classes().get(current.superName());
        }
        return false;
    }

    /** The first library classes reached when walking up from a local class. */
    private Set<String> libraryAncestors(Context ctx, String localClass) {
        Set<String> out = new LinkedHashSet<>();
        collectAncestors(ctx, localClass, out, new LinkedHashSet<>());
        return out;
    }

    private void collectAncestors(Context ctx, String name, Set<String> out, Set<String> seen) {
        if (!seen.add(name)) {
            return;
        }
        ScannedClass c = ctx.scan().classes().get(name);
        if (c == null) {
            if (oldModel.contains(name)) {
                out.add(name);
            }
            return;
        }
        if (c.superName() != null) {
            collectAncestors(ctx, c.superName(), out, seen);
        }
        for (String i : c.interfaces()) {
            collectAncestors(ctx, i, out, seen);
        }
    }

    private boolean declaredLocally(Context ctx, String localClass, Usage u) {
        ScannedClass c = ctx.scan().classes().get(localClass);
        Set<String> seen = new LinkedHashSet<>();
        while (c != null && seen.add(c.name())) {
            if (u.isField() ? c.fields().contains(u.name()) : c.declaresMethod(u.name(), u.descriptor())) {
                return true;
            }
            c = c.superName() == null ? null : ctx.scan().classes().get(c.superName());
        }
        return false;
    }

    private boolean accessible(Context ctx, Usage u, ClassInfo declaring, int access) {
        if (Access.isPublic(access)) {
            return true;
        }
        if (Access.isPrivate(access)) {
            return false;
        }
        String callerPackage = packageOf(u.location().className());
        if (callerPackage.equals(declaring.packageName())) {
            return true;
        }
        if (Access.isProtected(access)) {
            // Protected members are reachable from subclasses.
            return libraryAncestorsReach(ctx, u.location().className(), declaring.name());
        }
        return false;
    }

    private boolean libraryAncestorsReach(Context ctx, String localClass, String target) {
        for (String a : libraryAncestors(ctx, localClass)) {
            if (newModel.isSubtypeOf(a, target)) {
                return true;
            }
        }
        return false;
    }

    private List<ApiChange> changesFor(String owner, ResolvedMethod before, Usage u) {
        List<ApiChange> out = new ArrayList<>(diff.forMethod(owner, u.name(), u.descriptor()));
        String declaring = before.declaringClass().name();
        if (!declaring.equals(owner)) {
            out.addAll(diff.forMethod(declaring, u.name(), u.descriptor()));
        }
        return out;
    }

    private List<ApiChange> deprecationsOf(String owner) {
        return diff.forClass(owner).stream().filter(c -> c.kind() == ChangeKind.DEPRECATED).toList();
    }

    private void warnings(Context ctx, Usage u, String action, List<ApiChange> changes) {
        for (ApiChange c : changes) {
            if (c.severity() == Severity.WARNING) {
                String reason = c.isClassLevel() && c.kind() == ChangeKind.DEPRECATED
                        ? "belongs to " + c.subject() + ", which " + c.reason() : c.reason();
                ctx.add(new Finding(Severity.WARNING, c.kind(), ctx.origin(), u.location(), action, reason, null,
                        c.hint()));
            }
        }
    }

    private void breakage(Context ctx, Usage u, ChangeKind kind, String action, String reason, String consequence,
            String hint) {
        ctx.add(new Finding(Severity.BREAKING, kind, ctx.origin(), u.location(), action, reason, consequence, hint));
    }

    private static ApiChange firstBreaking(List<ApiChange> changes) {
        return changes.stream().filter(c -> c.severity() == Severity.BREAKING).findFirst().orElse(null);
    }

    private static ApiChange first(List<ApiChange> changes, ChangeKind kind) {
        return changes.stream().filter(c -> c.kind() == kind).findFirst().orElse(null);
    }

    private static String firstHint(List<ApiChange> changes) {
        return changes.stream().map(ApiChange::hint).filter(h -> h != null).findFirst().orElse(null);
    }

    private static String packageOf(String internalName) {
        int i = internalName.lastIndexOf('/');
        return i < 0 ? "" : internalName.substring(0, i);
    }
}
