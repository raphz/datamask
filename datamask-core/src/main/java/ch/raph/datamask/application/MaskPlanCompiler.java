package ch.raph.datamask.application;

import ch.raph.datamask.domain.MaskPlan;

/**
 * Derives the masking program for a class.
 *
 * <p>A port rather than a concrete type because the reflective implementation is one of two
 * plausible answers: a build-time generated compiler would fit here unchanged, which is the route
 * to GraalVM native images without reflection metadata.
 */
public interface MaskPlanCompiler {

    /** Returns the cached plan for a type, compiling it on first use. */
    MaskPlan planFor(Class<?> type);
}
