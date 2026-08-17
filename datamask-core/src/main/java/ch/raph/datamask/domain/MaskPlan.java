package ch.raph.datamask.domain;

import java.util.List;

/**
 * The compiled masking program for one class: which members to read, what to do with each, and how
 * to reassemble the result.
 *
 * <p>A plan is derived once per class and cached, so the reflection cost is paid on the first
 * instance only.
 *
 * <p>A plan can also record that compilation <em>failed</em> — an inaccessible type whose members
 * cannot even be read. That is deliberately distinct from an opaque plan: an opaque type is safe to
 * pass through, a failed one is not, because nothing proves it carries no PII.
 *
 * @param failure why the plan could not be compiled, or {@code null} for a usable plan
 */
public record MaskPlan(Class<?> type, List<MemberPlan> members, ValueRebuilder rebuilder, String failure) {

    public MaskPlan {
        members = List.copyOf(members);
    }

    public MaskPlan(Class<?> type, List<MemberPlan> members, ValueRebuilder rebuilder) {
        this(type, members, rebuilder, null);
    }

    /**
     * A plan for a type the engine cannot or need not take apart — a JDK value type, a class with
     * no accessible members, a proxy. Values of an opaque type are passed through untouched.
     */
    public static MaskPlan opaque(Class<?> type) {
        return new MaskPlan(type, List.of(), (original, values) -> original);
    }

    /**
     * A plan for a type whose members could not be read at all. The engine treats values of such a
     * type as a structural failure — redacted or thrown per the policy, never passed through,
     * because an unreadable type may carry PII that nothing was able to inspect.
     */
    public static MaskPlan failed(Class<?> type, String reason) {
        return new MaskPlan(
                type,
                List.of(),
                (original, values) -> {
                    throw new IllegalStateException("no masked copy of " + type.getName() + ": " + reason);
                },
                reason);
    }

    /** Whether compilation failed; see {@link #failed}. */
    public boolean isFailed() {
        return failure != null;
    }

    public boolean isOpaque() {
        return failure == null && members.isEmpty();
    }

    /** Whether any member of this class is masked outright, as opposed to merely traversed. */
    public boolean masksDirectly() {
        return members.stream().anyMatch(MemberPlan::masks);
    }
}
