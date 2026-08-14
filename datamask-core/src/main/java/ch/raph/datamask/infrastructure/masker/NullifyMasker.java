package ch.raph.datamask.infrastructure.masker;

import ch.raph.datamask.api.MaskContext;
import ch.raph.datamask.api.Masker;

/**
 * Removes the value entirely, so that not even its length or presence is disclosed. Useful for
 * fields whose mere existence is informative, such as a politically-exposed-person flag.
 */
public final class NullifyMasker implements Masker {

    @Override
    public Object mask(Object value, MaskContext context) {
        return null;
    }
}
