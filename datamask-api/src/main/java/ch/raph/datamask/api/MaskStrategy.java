package ch.raph.datamask.api;

/**
 * The built-in ways a value can be made safe to log, trace, publish or send to a model.
 *
 * <p>The strategies differ in what they preserve, and that is the only interesting axis: masking
 * is useless if the resulting log cannot be used to support a customer, and dangerous if it
 * preserves more than it needs to.
 */
public enum MaskStrategy {

    /** Resolve at runtime from the category, the declared type, then the value's own content. */
    AUTO,

    /** Replace the whole value. Preserves nothing. */
    REDACT,

    /** Keep a short trailing window, hide the rest — {@code ****6827}. */
    PARTIAL,

    /**
     * Replace with a keyed HMAC-SHA-256 digest. Preserves equality, so the same customer can still
     * be followed across services and log lines, without the value being recoverable. This is
     * pseudonymisation in the sense of GDPR Article 4(5) — an unkeyed digest of a low-entropy value
     * such as an IBAN would not be, because it is trivially brute-forced.
     */
    HASH,

    /** Replace with a random surrogate that a vault can map back. Reversible, by authorised callers only. */
    TOKENIZE,

    /** Drop the value entirely — it is not even represented in the output. */
    NULLIFY,

    /** {@code j***@e****.com} — keeps the shape and the TLD, hides local part and domain. */
    EMAIL,

    /** {@code CH93 **** **** **** 6827} — keeps country, check digits and the last four. */
    IBAN,

    /** PCI-DSS 3.3 compliant card masking: at most the first six and last four digits survive. */
    PAN,

    /** {@code +41*******67} — keeps the country calling code and the last two digits. */
    PHONE,

    /** {@code J*** D***} — keeps each initial, which is enough for a support agent to confirm identity. */
    NAME,

    /** Zeroes the host portion — {@code 192.168.1.0}, {@code 2001:db8::} — as recommended for IP logs. */
    IP,

    /** Widens a date to its year, so age cohorts stay analysable but the birth date does not leak. */
    DATE_GENERALIZE,

    /**
     * Replace each character with another of the same class, preserving length and layout. Useful
     * when a downstream system validates the format of what it receives.
     */
    PRESERVE_FORMAT,

    /** Scan free-form text and mask only the fragments detected as PII, leaving prose intact. */
    SCAN
}
