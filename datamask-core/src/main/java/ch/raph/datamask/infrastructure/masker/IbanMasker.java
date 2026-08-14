package ch.raph.datamask.infrastructure.masker;

import ch.raph.datamask.api.MaskContext;
import ch.raph.datamask.api.Masker;
import ch.raph.datamask.infrastructure.detect.Checksums;

/**
 * {@code CH9300762011623852957} becomes {@code CH93 **** **** **** *2957}.
 *
 * <p>The country code and check digits stay because they identify the scheme rather than the
 * holder, and the last four are what a customer reads back over the phone.
 */
public final class IbanMasker implements Masker {

    private static final int LEADING_VISIBLE = 4;
    private static final int TRAILING_VISIBLE = 4;

    @Override
    public Object mask(Object value, MaskContext context) {
        String compact = Checksums.compact(Masks.text(value));
        if (compact.length() < LEADING_VISIBLE + TRAILING_VISIBLE + 1) {
            return Masks.placeholder(context);
        }

        int keep = context.keep() >= 0 ? Math.min(context.keep(), TRAILING_VISIBLE) : TRAILING_VISIBLE;
        char padding = context.padding();

        String masked = compact.substring(0, LEADING_VISIBLE)
                + Masks.repeat(padding, compact.length() - LEADING_VISIBLE - keep)
                + compact.substring(compact.length() - keep);

        return group(masked);
    }

    /** Renders in the printed IBAN format: groups of four, separated by a single space. */
    private static String group(String compact) {
        StringBuilder out = new StringBuilder(compact.length() + compact.length() / 4);
        for (int i = 0; i < compact.length(); i++) {
            if (i > 0 && i % 4 == 0) {
                out.append(' ');
            }
            out.append(compact.charAt(i));
        }
        return out.toString();
    }
}
