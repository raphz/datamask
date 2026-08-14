package ch.raph.datamask.domain;

/**
 * Produces a new instance of a type from its masked member values.
 *
 * <p>Masking returns a new object rather than mutating the original, because the original is
 * almost always still needed by the business logic that is being logged.
 */
@FunctionalInterface
public interface ValueRebuilder {

    /**
     * @param original     the instance that was masked, for types that need to copy state the plan
     *                     does not cover
     * @param memberValues masked values, in the order of {@link MaskPlan#members()}
     */
    Object rebuild(Object original, Object[] memberValues) throws Throwable;
}
