package ch.raph.datamask.domain;

import ch.raph.datamask.api.MaskStrategy;
import ch.raph.datamask.api.PiiCategory;

/**
 * Receives what the engine did, for metrics and audit.
 *
 * <p>{@link #onUnannotatedPii} is the interesting signal: it fires when a detector finds PII in a
 * value nobody annotated, which is the earliest warning that a new field is leaking. Alerting on
 * it turns the library from a mask into a control.
 *
 * <p>Implementations run on the masking hot path and must be cheap and non-throwing.
 */
public interface MaskingObserver {

    MaskingObserver NOOP = new MaskingObserver() {};

    /** A declared PII value was masked. */
    default void onMasked(String path, PiiCategory category, MaskStrategy strategy) {}

    /** A detector found PII in a value that carried no annotation. */
    default void onUnannotatedPii(String path, PiiCategory category, String detector) {}

    /** Masking failed and the configured {@link FailureMode} was applied. */
    default void onFailure(String path, Throwable error) {}

    /** Traversal stopped early because {@link MaskingPolicy#maxDepth()} was reached. */
    default void onDepthLimitExceeded(String path) {}
}
