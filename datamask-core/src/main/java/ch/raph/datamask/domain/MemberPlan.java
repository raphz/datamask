package ch.raph.datamask.domain;

/** One field or record component of a compiled {@link MaskPlan}. */
public record MemberPlan(String name, Class<?> declaredType, MemberAccessor accessor, MaskAction action) {

    public boolean masks() {
        return action instanceof MaskAction.Mask || action instanceof MaskAction.Drop;
    }
}
