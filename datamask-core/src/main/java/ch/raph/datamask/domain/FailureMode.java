package ch.raph.datamask.domain;

/** What the engine does when masking a value fails unexpectedly. */
public enum FailureMode {

    /**
     * Redact the value and carry on. The default, and the only defensible choice in production:
     * a masking bug must degrade to less information, never to more.
     */
    REDACT,

    /**
     * Propagate the failure. Appropriate in tests, where a masking bug should break the build
     * rather than quietly redact a field nobody notices.
     */
    THROW,

    /**
     * Pass the original value through. Leaks PII by construction and exists only so a developer
     * can debug a masking problem locally; the engine refuses this mode under a strict policy.
     */
    PASS_THROUGH
}
