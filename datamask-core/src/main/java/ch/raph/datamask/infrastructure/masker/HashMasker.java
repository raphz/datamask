package ch.raph.datamask.infrastructure.masker;

import ch.raph.datamask.api.MaskContext;
import ch.raph.datamask.api.Masker;

/**
 * Replaces the value with a keyed surrogate that is stable across services and restarts, so a
 * customer's requests can still be correlated in a log aggregator without the customer being
 * identifiable from it.
 */
public final class HashMasker implements Masker {

    @Override
    public Object mask(Object value, MaskContext context) {
        return context.pseudonymize(Masks.text(value));
    }
}
