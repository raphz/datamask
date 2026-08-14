package ch.raph.datamask.infrastructure.masker;

import ch.raph.datamask.api.MaskContext;
import ch.raph.datamask.api.Masker;

/** Replaces the value with a surrogate an authorised caller can exchange back through the vault. */
public final class TokenizeMasker implements Masker {

    @Override
    public Object mask(Object value, MaskContext context) {
        return context.tokenize(Masks.text(value));
    }
}
