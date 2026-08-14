package ch.raph.datamask.infrastructure.generated;

import ch.raph.datamask.domain.MaskPlan;

/**
 * One masking plan that was worked out while the code was compiled rather than while it runs.
 *
 * <p>{@code datamask-build-processor} emits an implementation of this per annotated type, next to
 * the type it describes, and lists it in {@code META-INF/services}. Nothing calls it directly:
 * {@link GeneratedMaskPlanCompiler} loads every implementation on the classpath and answers from
 * that map instead of reflecting over the class.
 *
 * <p>Implementations are generated. Writing one by hand is possible and occasionally useful — a
 * plan for a third-party type the processor cannot see — but the generated ones are the reason this
 * interface exists, so keep any hand-written implementation to the same shape: a public class with a
 * public no-argument constructor, and a {@link #plan()} that builds the same {@code MaskPlan} the
 * reflective compiler would.
 */
public interface GeneratedMaskPlan {

    /** The class this plan masks. Must be the exact runtime class, not a supertype. */
    Class<?> type();

    /**
     * Builds the plan. Called once per type when the compiler is constructed, so the accessors and
     * the rebuilder it captures are what every subsequent masking of this type uses.
     */
    MaskPlan plan();
}
