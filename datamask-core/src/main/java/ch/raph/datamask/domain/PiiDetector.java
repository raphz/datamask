package ch.raph.datamask.domain;

import java.util.List;

/**
 * Finds personal data inside free-form text that nobody annotated.
 *
 * <p>Annotations cover the data a developer knew about. Detectors cover the payment reference that
 * a customer typed their own IBAN into, the exception message that quotes a row, and the support
 * note pasted into a ticket — which is where accidental disclosure actually happens.
 *
 * <p>Implementations must be thread-safe.
 */
public interface PiiDetector {

    /** Stable identifier, reported to the {@link MaskingObserver} so findings can be attributed. */
    String name();

    /** Findings in document order. Overlaps are resolved by the caller, longest and most confident first. */
    List<PiiFinding> detect(CharSequence text);

    /**
     * A cheap necessary condition for this detector having anything to find, checked against a
     * one-pass summary of the text before {@link #detect} is called at all.
     *
     * <p>Answering {@code false} skips this detector entirely, which is where nearly all of the
     * scanning cost goes on text that carries no PII — the common case by a wide margin. A pattern
     * that needs an {@code @}, or twelve digits, or six consecutive capitals, can say so here and
     * never run on text that has none of them.
     *
     * <p><strong>Answer {@code true} whenever there is any doubt.</strong> A wrong {@code true}
     * costs one pattern match. A wrong {@code false} is a value that is never examined and therefore
     * never masked — a silent leak, of exactly the kind this library exists to prevent, and one no
     * test of the detector itself would catch. Only exclude text the pattern <em>provably</em>
     * cannot match, and add the positive fixture that proves it.
     *
     * <p>The default answers {@code true} for everything, so a detector that says nothing here keeps
     * running exactly as before.
     */
    default boolean mightMatch(TextSignals signals) {
        return true;
    }
}
