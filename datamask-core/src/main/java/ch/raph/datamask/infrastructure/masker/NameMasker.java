package ch.raph.datamask.infrastructure.masker;

import ch.raph.datamask.api.MaskContext;
import ch.raph.datamask.api.Masker;

/** {@code Jean-Pierre Dubois} becomes {@code J****-P***** D*****}. */
public final class NameMasker implements Masker {

    @Override
    public Object mask(Object value, MaskContext context) {
        String text = Masks.text(value);
        char padding = context.padding();
        StringBuilder out = new StringBuilder(text.length());
        boolean atWordStart = true;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isLetter(c)) {
                out.append(c);
                atWordStart = true;
            } else if (atWordStart) {
                out.append(c);
                atWordStart = false;
            } else {
                out.append(padding);
            }
        }
        return out.toString();
    }
}
