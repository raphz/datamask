package ch.raph.datamask.domain;

import ch.raph.datamask.api.MaskStrategy;
import ch.raph.datamask.api.PiiCategory;

/**
 * Receives what the engine did, for metrics and audit.
 *
 * <p>{@link #onUnannotatedPii} is the interesting signal: it fires when a detector finds PII in a
 * value nobody annotated, which is the earliest warning that a new field is leaking. Alerting on
 * it turns the library from a mask into a control. Everything else here exists so that signal stays
 * worth alerting on — {@link #onScanned} and {@link #onCollectionTruncated} carry the events that
 * used to arrive through {@code onUnannotatedPii} and {@code onDepthLimitExceeded} and dilute them.
 *
 * <p>Implementations run on the masking hot path and must be cheap and non-throwing.
 */
public interface MaskingObserver {

    MaskingObserver NOOP = new MaskingObserver() {};

    /** A declared PII value was masked. */
    default void onMasked(String path, PiiCategory category, MaskStrategy strategy) {}

    /**
     * A detector found PII in a value that carried no annotation.
     *
     * <p>The one to alert on. A hit here is a field whose author did not know it holds personal
     * data, which is a different and more urgent thing than a field that was declared free text and
     * scanned as designed — that arrives at {@link #onScanned}.
     */
    default void onUnannotatedPii(String path, PiiCategory category, String detector) {}

    /**
     * A detector found PII inside a value that was explicitly declared for scanning — a
     * {@code FREEFORM_TEXT} category or a {@code SCAN} strategy.
     *
     * <p>Working as intended, not a warning. Worth counting; not worth paging anyone.
     */
    default void onScanned(String path, PiiCategory category, String detector) {}

    /** Masking failed and the configured {@link FailureMode} was applied. */
    default void onFailure(String path, Throwable error) {}

    /** Traversal stopped early because {@link MaskingPolicy#maxDepth()} was reached. */
    default void onDepthLimitExceeded(String path) {}

    /**
     * A collection or map was cut short at {@link MaskingPolicy#maxCollectionElements()}, and
     * {@code kept} elements were masked into the copy. The tail is dropped, which discloses nothing.
     *
     * <p>Separate from {@link #onDepthLimitExceeded} because the two say different things about the
     * data and want different responses: a deep graph is usually a modelling surprise, a truncated
     * collection is usually a volume one. The path is the container's, not a synthesised index —
     * the list and map variants used to report {@code path[7]} and {@code path{7}} for the same
     * event, so nothing downstream could group them.
     */
    default void onCollectionTruncated(String path, int kept) {}
}
