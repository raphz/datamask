package ch.raph.datamask.processor.plan;

import java.util.List;

/**
 * Everything {@link PlanWriter} needs to emit one plan, and nothing about how it is written.
 *
 * <p>The split exists so the hard part — deciding what each member's {@code MaskAction} is, and
 * whether the type can be rebuilt without reflection at all — can be read and tested without a line
 * of string building next to it.
 *
 * <p>Every type here is already a source-level name: {@code java.util.List},
 * {@code ch.raph.datamask.testdomain.Banking.Customer}, {@code int}. The analyser resolves erasures
 * once so the writer never has to think about generics.
 */
record PlanModel(
        String packageName, String generatedSimpleName, String targetName, List<Member> members, Rebuild rebuild) {

    /** One field or record component: how to read it, and what the engine does with what it reads. */
    record Member(String name, String declaredType, String readExpression, Action action) {}

    /** What the engine does with a member — the emit-time shape of {@code MaskAction}. */
    sealed interface Action {

        /** {@code MaskAction.KEEP}: copied across untouched. */
        record Keep() implements Action {}

        /** {@code MaskAction.DESCEND}: recursed into. */
        record Descend() implements Action {}

        /** {@code MaskAction.Mask}: replaced, using a descriptor resolved at compile time. */
        record Mask(Descriptor descriptor) implements Action {}

        Action KEEP = new Keep();
        Action DESCEND = new Descend();
    }

    /**
     * A {@code PiiDescriptor}'s constructor arguments, in order.
     *
     * <p>Deliberately the arguments and not the resolved values: the generated code calls the real
     * constructor, so the compact constructor that forces {@code keep = 0} for a category that is
     * never partially revealed still runs. Emitting the fields directly would let a card
     * verification value be generated with a {@code keep} the runtime would have refused.
     */
    record Descriptor(
            String category,
            String sensitivity,
            String strategy,
            int keep,
            char padding,
            String replacement,
            String maskerType,
            String purpose) {}

    /** How a masked copy is built. Both shapes are direct calls — no constructor is ever reflected. */
    sealed interface Rebuild {

        /** {@code new Target(values...)}: a record's canonical constructor, or a bean's all-arguments one. */
        record Constructor(List<String> parameterTypes) implements Rebuild {}

        /** {@code new Target()} followed by a write per member, for a bean that has only a no-argument one. */
        record NoArgumentsThenWrite(List<Write> writes) implements Rebuild {}
    }

    /** One member write on a freshly constructed bean: a setter call, or an assignment. */
    record Write(String name, String declaredType, boolean setter) {}
}
