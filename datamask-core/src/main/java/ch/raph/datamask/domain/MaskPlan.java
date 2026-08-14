package ch.raph.datamask.domain;

import java.util.List;

/**
 * The compiled masking program for one class: which members to read, what to do with each, and how
 * to reassemble the result.
 *
 * <p>A plan is derived once per class and cached, so the reflection cost is paid on the first
 * instance only.
 */
public record MaskPlan(Class<?> type, List<MemberPlan> members, ValueRebuilder rebuilder) {

    public MaskPlan {
        members = List.copyOf(members);
    }

    /**
     * A plan for a type the engine cannot or need not take apart — a JDK value type, a class with
     * no accessible members, a proxy. Values of an opaque type are passed through untouched.
     */
    public static MaskPlan opaque(Class<?> type) {
        return new MaskPlan(type, List.of(), (original, values) -> original);
    }

    public boolean isOpaque() {
        return members.isEmpty();
    }

    /** Whether any member of this class is masked outright, as opposed to merely traversed. */
    public boolean masksDirectly() {
        return members.stream().anyMatch(MemberPlan::masks);
    }
}
