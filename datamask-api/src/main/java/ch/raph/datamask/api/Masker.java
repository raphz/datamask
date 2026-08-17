package ch.raph.datamask.api;

import org.jspecify.annotations.Nullable;

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
    @Nullable Object mask(Object value, MaskContext context);

    /**
     * Whether this masker can handle a value of this type. Returning {@code false} makes the engine
     * fall back to full redaction rather than risk a wrong-shaped output.
     *
     * <p>{@code type} is the value's <strong>runtime class</strong>, not the type its member was
     * declared as. A member declared {@code Object}, {@code CharSequence} or an interface says
     * nothing about what an implementation would have to handle, and answering "no" costs the value
     * its real masking, so the question is asked about what is actually there. The declared type is
     * still what the result has to fit, and {@link MaskContext#declaredType()} is where to read it.
     *
     * <p>Nothing is masked twice on the strength of this: a masker that cannot help is replaced,
     * never supplemented.
     */
    default boolean supports(Class<?> type) {
        return true;
    }
}
