package ch.raph.datamask.api;

/**
 * Turns one sensitive value into its safe representation.
 *
 * <p>This is the extension point for domain-specific formats the library does not know about — an
 * internal contract reference, a proprietary customer key. Implementations are given a
 * {@link MaskContext} so they can pseudonymise or tokenise without depending on the engine.
 *
 * <p>Implementations must be thread-safe and side-effect free, and must never return the value
 * they were given.
 */
@FunctionalInterface
public interface Masker {

    /**
     * @param value   the value to mask, never {@code null}
     * @param context the declaration being masked, plus the cryptographic services of the engine
     * @return the masked representation; {@code null} to drop the value entirely
     */
    Object mask(Object value, MaskContext context);

    /**
     * Whether this masker can handle the given declared type. Returning {@code false} makes the
     * engine fall back to full redaction rather than risk a wrong-shaped output.
     */
    default boolean supports(Class<?> type) {
        return true;
    }
}
