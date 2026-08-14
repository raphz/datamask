package ch.raph.datamask.infrastructure.masker;

import ch.raph.datamask.api.MaskContext;
import ch.raph.datamask.api.Masker;

/** Replaces the value outright. The fallback whenever anything else is uncertain. */
public final class RedactMasker implements Masker {

    @Override
    public Object mask(Object value, MaskContext context) {
        return Masks.placeholder(context);
    }
}
