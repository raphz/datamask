package ch.raph.datamask.infrastructure.masker;

import ch.raph.datamask.api.MaskContext;
import ch.raph.datamask.api.Masker;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Replaces every letter and digit with another of the same class, keeping length and layout
 * intact: {@code 020 7946 0958} might become {@code 031 4820 7715}.
 *
 * <p>This is format-preserving <em>masking</em>, not format-preserving encryption: the
 * substitution is derived from the keyed pseudonym of the input, so it is stable across runs and
 * machines — a masked data set stays joinable — but it is not reversible, and the output of a
 * checksummed format (an IBAN, a card number) will not validate.
 *
 * <p>Every codepoint classified as a letter is substituted, whatever its script — a Cyrillic or
 * Greek name is masked exactly like a Latin one, with Latin substitutes. Only characters that are
 * neither letters nor digits (separators, punctuation) survive, because they are the layout being
 * preserved.
 *
 * <p>The output is indistinguishable from a real value, which is the point and also the risk: it
 * must never be used where a reader needs to know they are looking at masked data.
 */
public final class FormatPreservingMasker implements Masker {

    @Override
    public Object mask(Object value, MaskContext context) {
        if (context.category().neverPartiallyReveal()) {
            return Masks.placeholder(context);
        }
        String text = Masks.text(value);
        if (text.isEmpty()) {
            return text;
        }

        Draws draws = new Draws(context.pseudonymize(text));
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> {
            if (Character.isDigit(cp)) {
                out.append((char) ('0' + draws.nextBelow(10)));
            } else if (Character.isUpperCase(cp) || Character.isTitleCase(cp)) {
                out.append((char) ('A' + draws.nextBelow(26)));
            } else if (Character.isLetter(cp)) {
                out.append((char) ('a' + draws.nextBelow(26)));
            } else {
                out.appendCodePoint(cp);
            }
        });
        return out.toString();
    }

    /**
     * A deterministic stream of small numbers seeded from the keyed pseudonym. SHA-256 over the
     * seed (re-hashed with a counter when a block runs out) gives uniform bytes, and rejection
     * sampling removes the modulo bias a plain {@code % bound} would introduce.
     */
    private static final class Draws {

        private final byte[] seed;
        private byte[] block;
        private int cursor;
        private int counter;

        Draws(String pseudonym) {
            this.seed = pseudonym.getBytes(StandardCharsets.UTF_8);
            this.block = digest(0);
        }

        int nextBelow(int bound) {
            int limit = 256 - (256 % bound);
            while (true) {
                int draw = next() & 0xFF;
                if (draw < limit) {
                    return draw % bound;
                }
            }
        }

        private byte next() {
            if (cursor == block.length) {
                block = digest(++counter);
                cursor = 0;
            }
            return block[cursor++];
        }

        private byte[] digest(int round) {
            try {
                MessageDigest sha = MessageDigest.getInstance("SHA-256");
                sha.update(seed);
                sha.update((byte) (round >>> 24));
                sha.update((byte) (round >>> 16));
                sha.update((byte) (round >>> 8));
                sha.update((byte) round);
                return sha.digest();
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is unavailable in this JVM", e);
            }
        }
    }
}
