package ch.raph.datamask.infrastructure.masker;

import ch.raph.datamask.api.MaskContext;
import ch.raph.datamask.api.Masker;

/**
 * Replaces every character with another of the same class, keeping length and layout intact:
 * {@code 020 7946 0958} might become {@code 031 4820 7715}.
 *
 * <p>For test-data generation and for downstream systems that validate the shape of what they
 * receive. The substitution is derived from the keyed pseudonym of the input, so it is stable
 * across runs and machines — a masked data set stays joinable — but it is not reversible.
 *
 * <p>The output is indistinguishable from a real value, which is the point and also the risk: it
 * must never be used where a reader needs to know they are looking at masked data.
 */
public final class FormatPreservingMasker implements Masker {

    @Override
    public Object mask(Object value, MaskContext context) {
        String text = Masks.text(value);
        if (text.isEmpty()) {
            return text;
        }

        byte[] entropy = context.pseudonymize(text).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        StringBuilder out = new StringBuilder(text.length());
        int cursor = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int draw = entropy[cursor++ % entropy.length] & 0x7F;
            if (Character.isDigit(c)) {
                out.append((char) ('0' + draw % 10));
            } else if (c >= 'a' && c <= 'z') {
                out.append((char) ('a' + draw % 26));
            } else if (c >= 'A' && c <= 'Z') {
                out.append((char) ('A' + draw % 26));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
