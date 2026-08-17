package ch.raph.datamask.api;

/**
 * How damaging disclosure of a value would be. A {@code MaskingPolicy} masks everything at or
 * above its threshold, which is how a single annotated domain can be logged verbosely in a
 * developer sandbox and strictly in production without changing any code.
 */
public enum Sensitivity {

    /** Disclosure is a nuisance: a country, a locale, a customer segment. */
    LOW,

    /** Disclosure is a privacy incident but not a reportable breach on its own. */
    MEDIUM,

    /** Disclosure is a reportable personal-data breach: names, emails, account numbers. */
    HIGH,

    /** Disclosure is a regulatory incident: card data, credentials, national identifiers. */
    CRITICAL;

    public boolean atLeast(Sensitivity threshold) {
        return compareTo(threshold) >= 0;
    }
}
