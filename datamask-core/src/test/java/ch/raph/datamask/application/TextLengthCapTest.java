package ch.raph.datamask.application;

import static org.assertj.core.api.Assertions.assertThat;

import ch.raph.datamask.api.PiiCategory;
import ch.raph.datamask.domain.MaskingObserver;
import ch.raph.datamask.domain.MaskingPolicy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What happens to text longer than {@link MaskingPolicy#maxTextLength()}.
 *
 * <p>Scanning is linear in the length of the string, and before this cap existed there was no bound
 * on it at all: a two-kilobyte message cost a third of a millisecond and a two-hundred-kilobyte one
 * cost thirty, on whatever thread was logging. The cap is a bound on that, and the direction it
 * fails in is the whole question — everything past it must be redacted rather than emitted unread,
 * or the cap would be a way to smuggle a value past the scanner by putting enough text in front
 * of it.
 */
@DisplayName("The text length cap")
class TextLengthCapTest {

    private static final String IBAN = "CH9300762011623852957";
    private static final String CARD = "4111111111111111";

    /** Small enough to make the cases readable; the production default is eight thousand. */
    private static final int CAP = 64;

    static final class Recorder implements MaskingObserver {
        final List<Integer> truncated = new ArrayList<>();
        final List<PiiCategory> found = new ArrayList<>();

        @Override
        public void onTextTruncated(String path, int scanned) {
            truncated.add(scanned);
        }

        @Override
        public void onUnannotatedPii(String path, PiiCategory category, String detector) {
            found.add(category);
        }
    }

    private final Recorder recorder = new Recorder();

    private DataMask capped() {
        return DataMask.builder()
                .secret("length-cap-test-secret-not-a-real-key")
                .policy(MaskingPolicy.strict().withMaxTextLength(CAP))
                .observer(recorder)
                .build();
    }

    @Test
    @DisplayName("leaves text within the cap exactly as it was")
    void doesNotTouchShortText() {
        String text = "settlement batch accepted";

        assertThat(capped().maskText(text)).isEqualTo(text);
        assertThat(recorder.truncated).isEmpty();
    }

    @Test
    @DisplayName("masks what is inside the cap and redacts everything after it")
    void redactsPastTheCap() {
        String text = "reference " + IBAN + " " + "x".repeat(200) + " trailing note";

        String masked = capped().maskText(text);

        assertThat(masked).doesNotContain(IBAN).doesNotContain("trailing note").endsWith("****");
        assertThat(recorder.truncated).hasSize(1);
        assertThat(recorder.found).contains(PiiCategory.IBAN);
    }

    @Test
    @DisplayName("does not leave PII beyond the cap in the output")
    void neverEmitsUnscannedText() {
        String text = "y".repeat(200) + " card " + CARD;

        assertThat(capped().maskText(text)).doesNotContain(CARD);
    }

    @Test
    @DisplayName("cuts before a value that straddles the cap rather than through it")
    void doesNotCutThroughAValue() {
        // The card number starts inside the cap and ends outside it. Cutting at the cap would leave
        // its first digits in the output — a partial card number that the cap itself created, which
        // is exactly the disclosure this library exists to prevent.
        String text = "a".repeat(CAP - 8) + " " + CARD + " " + "b".repeat(100);

        String masked = capped().maskText(text);

        assertThat(masked).doesNotContain(CARD).doesNotContain(CARD.substring(0, 8));
    }

    @Test
    @DisplayName("keeps the prefix of long text that carries nothing, since there is nothing to hide in it")
    void keepsThePrefixOfCleanText() {
        String masked = capped().maskText("z".repeat(CAP * 2));

        assertThat(masked).isEqualTo("z".repeat(CAP) + "****");
        assertThat(recorder.truncated).containsExactly(CAP);
    }

    @Test
    @DisplayName("redacts the whole string when a detected value straddles the cap from the start")
    void redactsAValueLongerThanTheCap() {
        // A PEM block is the one built-in finding that routinely runs past a cap. It starts at the
        // first character and ends past it, so there is no prefix that can be kept.
        String pem = "-----BEGIN PRIVATE KEY-----\n" + "A".repeat(40) + "\n-----END PRIVATE KEY-----";

        assertThat(capped().maskText(pem)).isEqualTo("****");
        assertThat(recorder.truncated).containsExactly(0);
    }

    @Test
    @DisplayName("bounds masking, and deliberately does not bound the audit call")
    void scanReadsTheWholeText() {
        // scan() reports what a payload contains and changes nothing. Answering it about the first
        // sixty-four characters of a document would be worse than answering it slowly.
        String text = "x".repeat(200) + " " + IBAN;

        assertThat(capped().scan(text)).isNotEmpty();
    }

    @Test
    @DisplayName("declines to classify an oversized value, which its callers read as a reason to redact it")
    void classifyIsBoundedToo() {
        // Both callers — AUTO resolution in the engine, and a JDBC bind parameter — redact when
        // classify has no answer, so the bound costs output and never disclosure.
        DataMask capped = capped();
        String oversized = IBAN + " " + "x".repeat(200);

        assertThat(capped.engine().sanitizer().classify(oversized)).isEmpty();
        assertThat(capped.engine().sanitizer().classify(IBAN)).contains(PiiCategory.IBAN);
    }

    @Test
    @DisplayName("applies to a string reached through an object graph, not only to maskText")
    void appliesInsideTheEngine() {
        record Note(String body) {}

        Note masked = capped().mask(new Note("x".repeat(200) + " " + IBAN));

        assertThat(masked.body()).doesNotContain(IBAN).endsWith("****");
    }

    @Test
    @DisplayName("is a policy value, so a deployment that logs whole payloads can raise it")
    void isConfigurable() {
        String text = "x".repeat(200) + " " + IBAN;

        DataMask generous = DataMask.builder()
                .secret("length-cap-test-secret-not-a-real-key")
                .policy(MaskingPolicy.strict().withMaxTextLength(10_000))
                .build();

        assertThat(generous.maskText(text)).doesNotContain(IBAN).doesNotEndWith("****");
    }
}
