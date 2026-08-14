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
}
