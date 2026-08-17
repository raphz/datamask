package ch.raph.datamask.api;

/**
 * What kind of personal data a value holds.
 *
 * <p>The category is the declaration a developer can make confidently — "this is an IBAN" — while
 * the strategy is a policy decision that may differ between environments. Annotating the category
 * and leaving the strategy on {@link MaskStrategy#AUTO} is therefore the recommended style.
 */
public enum PiiCategory {
    UNSPECIFIED(MaskStrategy.AUTO, -1),

    EMAIL(MaskStrategy.EMAIL, -1),
    PHONE(MaskStrategy.PHONE, 2),
    FULL_NAME(MaskStrategy.NAME, -1),
    POSTAL_ADDRESS(MaskStrategy.REDACT, -1),
    DATE_OF_BIRTH(MaskStrategy.DATE_GENERALIZE, -1),

    /** International Bank Account Number. */
    IBAN(MaskStrategy.IBAN, 4),
    /** Any other account or contract number. */
    ACCOUNT_NUMBER(MaskStrategy.PARTIAL, 4),
    /** Business Identifier Code. Not personal on its own, but it narrows an account holder. */
    BIC(MaskStrategy.PARTIAL, 3),
    /** Primary Account Number — a payment card number. Governed by PCI-DSS. */
    PAN(MaskStrategy.PAN, 4, Sensitivity.CRITICAL),
    /** Card verification value. Storing or logging it is prohibited outright, so never partial. */
    CARD_VERIFICATION_VALUE(MaskStrategy.REDACT, 0, Sensitivity.CRITICAL),
    /** Card expiry date. */
    CARD_EXPIRY(MaskStrategy.REDACT, 0, Sensitivity.CRITICAL),

    /** National identifier: Swiss AVS/AHV, US SSN, and equivalents. */
    NATIONAL_ID(MaskStrategy.PARTIAL, 0, Sensitivity.CRITICAL),
    TAX_ID(MaskStrategy.PARTIAL, 0, Sensitivity.CRITICAL),
    /** Passport or identity-card number. */
    IDENTITY_DOCUMENT(MaskStrategy.PARTIAL, 0, Sensitivity.CRITICAL),

    /**
     * A stable internal identifier for a person. Hashed rather than redacted, because correlating
     * a customer's requests across services is the whole point of having it in a log.
     */
    CUSTOMER_ID(MaskStrategy.HASH, -1),

    /** Passwords, API keys, bearer tokens, private keys. Never partially revealed. */
    CREDENTIAL(MaskStrategy.REDACT, 0, Sensitivity.CRITICAL),
    BIOMETRIC(MaskStrategy.REDACT, 0, Sensitivity.CRITICAL),

    IP_ADDRESS(MaskStrategy.IP, -1),
    DEVICE_ID(MaskStrategy.HASH, -1),
    GEO_LOCATION(MaskStrategy.REDACT, -1),

    /** A monetary amount or balance — not identifying alone, but disclosive when joined. */
    FINANCIAL_AMOUNT(MaskStrategy.REDACT, 0),

    /** Prose that may contain anything: a payment reference, a note, a support ticket body. */
    FREEFORM_TEXT(MaskStrategy.SCAN, -1);

    private final MaskStrategy defaultStrategy;
    private final int defaultKeep;
    private final Sensitivity defaultSensitivity;

    PiiCategory(MaskStrategy defaultStrategy, int defaultKeep) {
        this(defaultStrategy, defaultKeep, Sensitivity.HIGH);
    }

    PiiCategory(MaskStrategy defaultStrategy, int defaultKeep, Sensitivity defaultSensitivity) {
        this.defaultStrategy = defaultStrategy;
        this.defaultKeep = defaultKeep;
        this.defaultSensitivity = defaultSensitivity;
    }

    /** The strategy applied when the annotation leaves it on {@link MaskStrategy#AUTO}. */
    public MaskStrategy defaultStrategy() {
        return defaultStrategy;
    }

    /** Trailing characters left visible by default; {@code -1} when the strategy decides. */
    public int defaultKeep() {
        return defaultKeep;
    }

    /**
     * The sensitivity applied when the annotation leaves {@code sensitivity} on its default. Card
     * data, credentials and national identifiers are {@link Sensitivity#CRITICAL}, so no policy
     * threshold can switch off their masking by accident.
     */
    public Sensitivity defaultSensitivity() {
        return defaultSensitivity;
    }

    /**
     * Whether revealing any part of the value is forbidden regardless of configuration. Card
     * verification values and credentials are never partially disclosed, even if a policy or an
     * annotation asks for it.
     */
    public boolean neverPartiallyReveal() {
        return this == CARD_VERIFICATION_VALUE || this == CREDENTIAL || this == BIOMETRIC || this == CARD_EXPIRY;
    }
}
