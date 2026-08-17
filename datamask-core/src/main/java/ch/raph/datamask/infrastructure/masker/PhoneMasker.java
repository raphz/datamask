package ch.raph.datamask.infrastructure.masker;

import ch.raph.datamask.api.MaskContext;
import ch.raph.datamask.api.Masker;

/**
 * {@code +41 79 123 45 67} becomes {@code +41 ** *** ** 67}.
 *
 * <p>Country prefix and last two digits survive: enough to tell a Swiss mobile from a French
 * landline when reading a log, not enough to call anyone.
 */
public final class PhoneMasker implements Masker {

    private static final int LEADING_DIGITS_VISIBLE = 2;
    private static final int TRAILING_DIGITS_VISIBLE = 2;

    @Override
    public Object mask(Object value, MaskContext context) {
        if (context.category().neverPartiallyReveal()) {
            return Masks.placeholder(context);
        }
        String text = Masks.text(value);
        int totalDigits = 0;
        for (int i = 0; i < text.length(); i++) {
            if (Character.isDigit(text.charAt(i))) {
                totalDigits++;
            }
        }
        if (totalDigits < 6) {
            return Masks.placeholder(context);
        }

        // The country calling code is only revealed when the number is written in international
        // form; a bare national number gives no reliable way to tell prefix from subscriber digits.
        int leadingVisible = text.startsWith("+") ? LEADING_DIGITS_VISIBLE : 0;
        char padding = context.padding();

        StringBuilder out = new StringBuilder(text.length());
        int seen = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isDigit(c)) {
                out.append(c);
                continue;
            }
            boolean visible = seen < leadingVisible || seen >= totalDigits - TRAILING_DIGITS_VISIBLE;
            out.append(visible ? c : padding);
            seen++;
        }
        return out.toString();
    }
}
