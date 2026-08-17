package ch.raph.datamask.infrastructure.masker;

import ch.raph.datamask.api.MaskContext;
import ch.raph.datamask.api.Masker;

/**
 * Leaves a short trailing window visible — enough for a support agent to confirm they are looking
 * at the right account, not enough to reconstruct it.
 */
public final class PartialMasker implements Masker {

    @Override
    public Object mask(Object value, MaskContext context) {
        // keep is already forced to 0 for these categories, but even the length-and-separator
        // shape keepTrailing preserves is more than a CVV or credential may disclose.
        if (context.category().neverPartiallyReveal()) {
            return Masks.placeholder(context);
        }
        String text = Masks.text(value);
        if (text.isEmpty()) {
            return text;
        }
        return Masks.keepTrailing(text, context.keep(), context.padding());
    }
}
