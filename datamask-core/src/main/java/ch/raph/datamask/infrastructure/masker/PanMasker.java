package ch.raph.datamask.infrastructure.masker;

import ch.raph.datamask.api.MaskContext;
import ch.raph.datamask.api.Masker;
import ch.raph.datamask.infrastructure.detect.Checksums;

/**
 * Payment card masking as PCI-DSS requirement 3.3 defines it: {@code **** **** **** 1111}.
 *
 * <p>The standard permits the first six and last four to be displayed, but only the last four are
 * needed to identify a card to its holder, so that is the default. A value that fails the Luhn
 * check is masked all the same — being wrong about what a card number looks like must not be a way
 * to leak one.
 */
public final class PanMasker implements Masker {

    private static final int TRAILING_VISIBLE = 4;

    @Override
    public Object mask(Object value, MaskContext context) {
        String digits = Checksums.digitsOnly(Masks.text(value));
        if (digits.length() < 12) {
            return Masks.placeholder(context);
        }

        char padding = context.padding();
        String masked = Masks.repeat(padding, digits.length() - TRAILING_VISIBLE)
                + digits.substring(digits.length() - TRAILING_VISIBLE);

        StringBuilder out = new StringBuilder(masked.length() + masked.length() / 4);
        for (int i = 0; i < masked.length(); i++) {
            if (i > 0 && i % 4 == 0) {
                out.append(' ');
            }
            out.append(masked.charAt(i));
        }
        return out.toString();
    }
}
