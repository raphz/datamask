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
        boolean scanUnannotatedText,
        boolean maskMapKeys) {

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
        return new MaskingPolicy(Sensitivity.LOW, FailureMode.REDACT, "****", 32, 1_000, true, true);
    }

    /**
     * Development default: only high-sensitivity data is hidden and free text is left alone, so
     * logs stay readable while card numbers and credentials still never appear.
     */
    public static MaskingPolicy relaxed() {
        return new MaskingPolicy(Sensitivity.HIGH, FailureMode.REDACT, "****", 32, 1_000, false, false);
    }

    /** Whether a value of the given sensitivity is masked under this policy. */
    public boolean applies(Sensitivity sensitivity) {
        return sensitivity.atLeast(threshold);
    }

    public MaskingPolicy withThreshold(Sensitivity newThreshold) {
        return new MaskingPolicy(
                newThreshold,
                failureMode,
                redactionPlaceholder,
                maxDepth,
                maxCollectionElements,
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
                scanUnannotatedText,
                maskMapKeys);
    }

    public MaskingPolicy withScanUnannotatedText(boolean scan) {
        return new MaskingPolicy(
                threshold, failureMode, redactionPlaceholder, maxDepth, maxCollectionElements, scan, maskMapKeys);
    }

    public MaskingPolicy withRedactionPlaceholder(String placeholder) {
        return new MaskingPolicy(
                threshold, failureMode, placeholder, maxDepth, maxCollectionElements, scanUnannotatedText, maskMapKeys);
    }

    public MaskingPolicy withMaskMapKeys(boolean mask) {
        return new MaskingPolicy(
                threshold,
                failureMode,
                redactionPlaceholder,
                maxDepth,
                maxCollectionElements,
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
                scanUnannotatedText,
                maskMapKeys);
    }

    public MaskingPolicy withMaxCollectionElements(int elements) {
        return new MaskingPolicy(
                threshold, failureMode, redactionPlaceholder, maxDepth, elements, scanUnannotatedText, maskMapKeys);
    }
}
