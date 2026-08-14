/**
 * Build-time generation of masking plans.
 *
 * <p>{@link ch.raph.datamask.processor.plan.MaskPlanProcessor} is the whole public surface: put the
 * module on the annotation processor path and every type it can reach gets its {@code MaskPlan}
 * worked out during compilation instead of by reflection on the first request that carries it.
 * Whatever it cannot reach still gets a reflective plan, so adoption is a line in a build file
 * rather than a migration.
 *
 * <p>Three classes, in the order the work happens: {@code PlanAnalyzer} decides what the plan says
 * and whether it can be written without reflection at all, {@code PlanWriter} turns that into
 * source, and the processor decides which types are worth planning and registers what it wrote.
 * {@code LeafTypes} is the compile-time twin of the runtime's type classification and is the one
 * place where a divergence from the reflective compiler could hide — which is what
 * {@code PlanEquivalenceTest} exists to catch.
 */
package ch.raph.datamask.processor.plan;
