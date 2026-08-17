package ch.raph.datamask.domain;

import ch.raph.datamask.api.Sensitivity;
import java.util.Objects;

/**
 * The environment-dependent half of masking. Annotations say what the data <em>is</em>; the policy
 * says how strictly this deployment treats it, which is what lets one annotated domain model be
 * logged verbosely in a sandbox and strictly in production.
 */
public record MaskingPolicy(
        Sensitivity threshold,
        FailureMode failureMode,
        String redactionPlaceholder,
        int maxDepth,
        int maxCollectionElements,
        int maxTextLength,
        boolean scanUnannotatedText,
        boolean maskMapKeys) {

    /**
     * How much of a string is scanned before the rest is redacted unread.
     *
     * <p>Eight thousand characters is well past any log line worth reading and well short of the
     * sizes that hurt: scanning costs roughly a microsecond per six characters, so this caps one
     * value at a few milliseconds where an uncapped 200 KB payload would have cost tens.
     */
    public static final int DEFAULT_MAX_TEXT_LENGTH = 8_192;

    public MaskingPolicy {
        Objects.requireNonNull(threshold, "threshold");
        Objects.requireNonNull(failureMode, "failureMode");
        Objects.requireNonNull(redactionPlaceholder, "redactionPlaceholder");
        if (maxDepth < 1) {
            throw new IllegalArgumentException("maxDepth must be >= 1, was " + maxDepth);
        }
        if (maxCollectionElements < 1) {
            throw new IllegalArgumentException("maxCollectionElements must be >= 1, was " + maxCollectionElements);
        }
        if (maxTextLength < 1) {
            throw new IllegalArgumentException("maxTextLength must be >= 1, was " + maxTextLength);
        }
    }

    /**
     * The canonical constructor without {@code maxTextLength}, which takes the default.
     *
     * <p>Here because the component was added after the record shipped, and every existing call site
     * spelling out seven arguments was written before there was a length to bound.
     */
    public MaskingPolicy(
            Sensitivity threshold,
            FailureMode failureMode,
            String redactionPlaceholder,
            int maxDepth,
            int maxCollectionElements,
            boolean scanUnannotatedText,
            boolean maskMapKeys) {
        this(
                threshold,
                failureMode,
                redactionPlaceholder,
                maxDepth,
                maxCollectionElements,
                DEFAULT_MAX_TEXT_LENGTH,
                scanUnannotatedText,
                maskMapKeys);
    }

    /**
     * Production default: mask everything annotated, scan free text for PII that nobody annotated,
     * mask map keys, and redact rather than throw when something goes wrong.
     *
     * <p>Keys are masked here and not under {@link #relaxed()} because a map keyed by email address
     * or customer reference is a common shape, and a key is as disclosing as a value. What it costs
     * is lookup semantics: the masked copy is no longer keyed by anything the caller can look up.
     */
    public static MaskingPolicy strict() {
        return new MaskingPolicy(
                Sensitivity.LOW, FailureMode.REDACT, "****", 32, 1_000, DEFAULT_MAX_TEXT_LENGTH, true, true);
    }

    /**
     * Development default: only high-sensitivity data is hidden and free text is left alone, so
     * logs stay readable while card numbers and credentials still never appear.
     */
    public static MaskingPolicy relaxed() {
        return new MaskingPolicy(
                Sensitivity.HIGH, FailureMode.REDACT, "****", 32, 1_000, DEFAULT_MAX_TEXT_LENGTH, false, false);
    }

    /** Whether a value of the given sensitivity is masked under this policy. */
    public boolean applies(Sensitivity sensitivity) {
        return sensitivity.atLeast(threshold);
    }

    // Every wither goes through the eight-argument constructor, deliberately. Calling the
    // seven-argument convenience one would compile and would silently reset maxTextLength to its
    // default, so a deployment that raised the cap would lose it the moment anything adjusted the
    // failure mode.

    public MaskingPolicy withThreshold(Sensitivity newThreshold) {
        return new MaskingPolicy(
                newThreshold,
                failureMode,
                redactionPlaceholder,
                maxDepth,
                maxCollectionElements,
                maxTextLength,
                scanUnannotatedText,
                maskMapKeys);
    }

    public MaskingPolicy withFailureMode(FailureMode newFailureMode) {
        return new MaskingPolicy(
                threshold,
                newFailureMode,
                redactionPlaceholder,
                maxDepth,
                maxCollectionElements,
                maxTextLength,
                scanUnannotatedText,
                maskMapKeys);
    }

    public MaskingPolicy withScanUnannotatedText(boolean scan) {
        return new MaskingPolicy(
                threshold,
                failureMode,
                redactionPlaceholder,
                maxDepth,
                maxCollectionElements,
                maxTextLength,
                scan,
                maskMapKeys);
    }

    public MaskingPolicy withRedactionPlaceholder(String placeholder) {
        return new MaskingPolicy(
                threshold,
                failureMode,
                placeholder,
                maxDepth,
                maxCollectionElements,
                maxTextLength,
                scanUnannotatedText,
                maskMapKeys);
    }

    public MaskingPolicy withMaskMapKeys(boolean mask) {
        return new MaskingPolicy(
                threshold,
                failureMode,
                redactionPlaceholder,
                maxDepth,
                maxCollectionElements,
                maxTextLength,
                scanUnannotatedText,
                mask);
    }

    public MaskingPolicy withMaxDepth(int depth) {
        return new MaskingPolicy(
                threshold,
                failureMode,
                redactionPlaceholder,
                depth,
                maxCollectionElements,
                maxTextLength,
                scanUnannotatedText,
                maskMapKeys);
    }

    public MaskingPolicy withMaxCollectionElements(int elements) {
        return new MaskingPolicy(
                threshold,
                failureMode,
                redactionPlaceholder,
                maxDepth,
                elements,
                maxTextLength,
                scanUnannotatedText,
                maskMapKeys);
    }

    /**
     * How many characters of a string are scanned. Everything past the cap is redacted rather than
     * emitted unscanned, so raising it buys output and costs time, and lowering it does the reverse
     * — neither direction trades against disclosure.
     */
    public MaskingPolicy withMaxTextLength(int characters) {
        return new MaskingPolicy(
                threshold,
                failureMode,
                redactionPlaceholder,
                maxDepth,
                maxCollectionElements,
                characters,
                scanUnannotatedText,
                maskMapKeys);
    }
}
