package dev.tremor.diff;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** All API changes between two versions, indexed for fast lookup by class and member. */
public final class ApiDiff {

    private final List<ApiChange> changes;
    private final Map<String, List<ApiChange>> byClass;
    private final Map<String, List<ApiChange>> byMember;

    public ApiDiff(List<ApiChange> changes) {
        this.changes = List.copyOf(changes);
        this.byClass = changes.stream().filter(ApiChange::isClassLevel)
                .collect(Collectors.groupingBy(ApiChange::className));
        Map<String, List<ApiChange>> members = new HashMap<>();
        for (ApiChange c : changes) {
            if (!c.isClassLevel()) {
                members.computeIfAbsent(memberKey(c.className(), c.memberName(), c.isField() ? null : c.memberDescriptor()),
                        k -> new java.util.ArrayList<>()).add(c);
            }
        }
        this.byMember = members;
    }

    public List<ApiChange> changes() {
        return changes;
    }

    /** Changes declared on their own class (not repeated for every subclass). */
    public List<ApiChange> ownChanges() {
        return changes.stream().filter(c -> !c.inherited()).toList();
    }

    public List<ApiChange> forClass(String className) {
        return byClass.getOrDefault(className, List.of());
    }

    public List<ApiChange> forMethod(String className, String name, String descriptor) {
        return byMember.getOrDefault(memberKey(className, name, descriptor), List.of());
    }

    public List<ApiChange> forField(String className, String name) {
        return byMember.getOrDefault(memberKey(className, name, null), List.of());
    }

    public long count(Severity severity) {
        return ownChanges().stream().filter(c -> c.severity() == severity).count();
    }

    private static String memberKey(String className, String name, String descriptor) {
        return className + "#" + name + (descriptor == null ? "" : descriptor);
    }
}
