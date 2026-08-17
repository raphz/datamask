package ch.raph.datamask.benchmarks;

import ch.raph.datamask.application.DataMask;
import ch.raph.datamask.domain.MaskingPolicy;

/**
 * The inputs every benchmark here shares, and the checks that keep them honest.
 *
 * <h2>The secret is fixed, and that is not incidental</h2>
 *
 * {@link DataMask#withDefaults()} derives a key from an ephemeral secret. Deriving it is HKDF work,
 * it happens once when the {@code DataMask} is built, and it therefore belongs in {@code @Setup} and
 * nowhere near a measured invocation — which is exactly where it would end up if a benchmark ever
 * built its engine inside the method it measures. A constant secret also makes a {@code HASH}
 * pseudonym the same string on every run, so two runs of the same benchmark on the same machine are
 * comparing the same work.
 *
 * <h2>A clean line has to actually be clean</h2>
 *
 * The whole point of the clean-line numbers is that they are the price of finding nothing. A
 * detector matching one word of what this class calls PII-free would quietly turn the headline
 * benchmark into a measurement of masking, and it would still look plausible. So every clean input
 * is put through {@link #requireNothingDetected} during setup and the run refuses to start if
 * anything matched.
 */
final class Fixtures {

    /**
     * Fixed, and long enough for {@code MaskKey.ofSecret} to accept it. It is in version control on
     * purpose: nothing it protects is real.
     */
    static final String SECRET = "datamask-benchmark-secret-not-a-production-key";

    /** A representative application log line with nothing in it worth masking. */
    static final String CLEAN_MESSAGE = "settlement batch accepted by the clearing gateway, retry window closed";

    /**
     * The same idea with the punctuation a real log line has: an order number, a timestamp, a node
     * id. Nothing in it is PII, but the digits and the colons open several detector gates that
     * {@link #CLEAN_MESSAGE} closes.
     *
     * <p>It is here to keep the gate numbers honest. A pre-filter measured only on prose would look
     * like a tenfold win on every line, and it is not: this is the line that says what the filter is
     * worth when the text has structure in it, which most log lines do.
     */
    static final String NOISY_CLEAN_MESSAGE = "order 8891273 accepted at 12:04:33 by node 7, retry window closed";

    /** The same line with an IBAN in it — the single most common piece of PII in a banking log. */
    static final String IBAN_MESSAGE =
            "settlement batch rejected for CH93 0076 2011 6238 5295 7 by the clearing gateway";

    /** And with a card number, which is the category that may never be partially revealed. */
    static final String PAN_MESSAGE = "authorisation declined for card 4111 1111 1111 1111 at terminal forty-two";

    /**
     * A longer clean line, near the size a structured application message reaches. The scan is
     * linear in the text, so the pair of clean benchmarks says how the cost grows with the line
     * rather than only what it is for one size.
     */
    static final String LONG_CLEAN_MESSAGE = longCleanMessage();

    /**
     * Sixty-four kilobytes of it: a payload logged whole, which is the shape the length cap exists
     * for. Well past {@link MaskingPolicy#maxTextLength()}, so the capped and uncapped engines do
     * visibly different amounts of work on it.
     */
    static final String OVERSIZED_CLEAN_MESSAGE = repeatTo(64 * 1_024);

    private Fixtures() {}

    /** The engine every benchmark measures, under strict policy — which is what a bank deploys. */
    static DataMask dataMask() {
        return DataMask.builder().secret(SECRET).build();
    }

    /**
     * The same engine with the text length cap effectively removed, so a benchmark can measure what
     * the cap is worth by measuring both sides of it in one run rather than by arithmetic.
     */
    static DataMask uncappedDataMask() {
        return DataMask.builder()
                .secret(SECRET)
                .policy(MaskingPolicy.strict().withMaxTextLength(Integer.MAX_VALUE))
                .build();
    }

    /**
     * Fails the run when a supposedly clean input turns out to match a detector. Called from
     * {@code @Setup}, so a fixture that drifted stops the benchmark instead of quietly changing what
     * it measures.
     */
    static void requireNothingDetected(DataMask dataMask, String text) {
        if (dataMask.maskText(text) != text) {
            throw new IllegalStateException(
                    "A benchmark input that is supposed to carry no PII was masked, so the clean-path numbers "
                            + "would be measuring masking instead: " + text);
        }
    }

    /**
     * Fails the run when an input that is supposed to carry PII comes back untouched — the mirror of
     * the check above, and the one that catches a detector or an annotation that stopped working.
     */
    static void requireSomethingMasked(Object original, Object masked) {
        if (original == masked) {
            throw new IllegalStateException(
                    "A benchmark input that is supposed to carry PII was returned unchanged, so the PII-path "
                            + "numbers would be measuring the short-circuit instead.");
        }
    }

    private static String longCleanMessage() {
        return repeatTo(2_000);
    }

    private static String repeatTo(int characters) {
        StringBuilder text = new StringBuilder(characters + CLEAN_MESSAGE.length() + 2);
        while (text.length() < characters) {
            text.append(CLEAN_MESSAGE).append("; ");
        }
        return text.toString();
    }
}
