package ch.raph.datamask.infrastructure.masker;

import ch.raph.datamask.api.MaskContext;
import ch.raph.datamask.api.Masker;
import ch.raph.datamask.infrastructure.detect.Checksums;

/**
 * {@code CH9300762011623852957} becomes {@code CH93 **** **** **** *2957}.
 *
 * <p>The country code and check digits stay because they identify the scheme rather than the
 * holder, and the last four are what a customer reads back over the phone.
 *
 * <p>That reasoning only holds when the value actually has the shape of an IBAN. Anything else —
 * a mislabelled password, an internal reference — is fully replaced: revealing eight characters
 * of a value this masker cannot vouch for would be a disclosure, not a mask.
 */
public final class IbanMasker implements Masker {

    private static final int LEADING_VISIBLE = 4;
    private static final int TRAILING_VISIBLE = 4;
    private static final int MINIMUM_IBAN_LENGTH = 15;
    private static final int MAXIMUM_IBAN_LENGTH = 34;

    @Override
    public Object mask(Object value, MaskContext context) {
        if (context.category().neverPartiallyReveal()) {
            return Masks.placeholder(context);
        }
        String compact = Checksums.compact(Masks.text(value));
        if (!looksLikeIban(compact)) {
            return Masks.placeholder(context);
        }

        int keep = context.keep() >= 0 ? Math.min(context.keep(), TRAILING_VISIBLE) : TRAILING_VISIBLE;
        char padding = context.padding();

        String masked = compact.substring(0, LEADING_VISIBLE)
                + Masks.repeat(padding, compact.length() - LEADING_VISIBLE - keep)
                + compact.substring(compact.length() - keep);

        return group(masked);
    }

    /** ISO 13616 shape: two letters, two check digits, and a plausible length. */
    private static boolean looksLikeIban(String compact) {
        return compact.length() >= MINIMUM_IBAN_LENGTH
                && compact.length() <= MAXIMUM_IBAN_LENGTH
                && Character.isLetter(compact.charAt(0))
                && Character.isLetter(compact.charAt(1))
                && Character.isDigit(compact.charAt(2))
                && Character.isDigit(compact.charAt(3));
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
