package ch.raph.datamask.api;

/**
 * Everything a {@link Masker} needs: the declaration it is acting on, and the engine's keyed
 * cryptographic services.
 *
 * <p>Exposing pseudonymisation and tokenisation here is what lets a custom masker stay in the
 * dependency-free {@code datamask-api} module while still producing correlatable output.
 */
public interface MaskContext {

    /** The declared category, never {@code null}; {@link PiiCategory#UNSPECIFIED} when unknown. */
    PiiCategory category();

    Sensitivity sensitivity();

    /** The resolved strategy — never {@link MaskStrategy#AUTO}, which is resolved before dispatch. */
    MaskStrategy strategy();

    /** Trailing characters that may remain visible, already clamped to what the category permits. */
    int keep();

    char padding();

    /** A fixed replacement configured on the annotation, or an empty string when none was given. */
    String replacement();

    /** Dotted path of the value being masked, such as {@code Customer.accounts[0].iban}. */
    String path();

    /** The statically declared type of the member, which may be wider than the runtime type. */
    Class<?> declaredType();

    /** The placeholder used for complete redaction, {@code ****} unless configured otherwise. */
    String redactionPlaceholder();

    /**
     * Keyed, deterministic digest of the value. Equal inputs give equal outputs for as long as the
     * key lives, so masked logs remain joinable; the value cannot be recovered from the output.
     */
    String pseudonymize(String value);

    /**
     * Reversible surrogate for the value, resolvable only through the configured token vault.
     * Throws if no vault is configured, because silently degrading to an irreversible value would
     * break callers that need to map the surrogate back.
     */
    String tokenize(String value);
}
