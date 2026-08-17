package ch.raph.datamask.domain;

import static org.assertj.core.api.Assertions.assertThat;

import ch.raph.datamask.api.MaskStrategy;
import ch.raph.datamask.api.PII;
import ch.raph.datamask.api.PiiCategory;
import ch.raph.datamask.application.DataMask;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Which observer method fires for which event.
 *
 * <p>This matters more than it looks. {@code onUnannotatedPii} is the one signal in this library
 * worth paging someone about — it means a field nobody annotated is carrying personal data — and it
 * used to fire for annotated free-text fields being scanned exactly as designed. A signal that fires
 * during normal operation is a signal somebody mutes. The same argument applies to
 * {@code onDepthLimitExceeded}, which fired both for a graph too deep to walk and for a collection
 * too long, with a synthesised index that differed between the list and map cases so nothing
 * downstream could even group them.
 */
@DisplayName("The observer signals say different things")
class ObserverSignalsTest {

    private static final String IBAN = "CH9300762011623852957";

    record Note(@PII(category = PiiCategory.FREEFORM_TEXT) String text) {}

    record Unannotated(String text) {}

    record Bag(List<String> values) {}

    record Keyed(Map<String, String> entries) {}

    /** Records what fired, so a test can assert on the difference rather than on a count. */
    static final class Recorder implements MaskingObserver {
        final List<String> unannotated = new ArrayList<>();
        final List<String> scanned = new ArrayList<>();
        final List<String> truncated = new ArrayList<>();
        final List<String> depth = new ArrayList<>();

        @Override
        public void onUnannotatedPii(String path, PiiCategory category, String detector) {
            unannotated.add(path);
        }

        @Override
        public void onScanned(String path, PiiCategory category, String detector) {
            scanned.add(path);
        }

        @Override
        public void onCollectionTruncated(String path, int kept) {
            truncated.add(path + "=" + kept);
        }

        @Override
        public void onDepthLimitExceeded(String path) {
            depth.add(path);
        }
    }

    private final Recorder recorder = new Recorder();

    private DataMask dataMask(MaskingPolicy policy) {
        return DataMask.builder()
                .secret("a-test-secret-of-sufficient-length")
                .policy(policy)
                .observer(recorder)
                .build();
    }

    @Nested
    @DisplayName("A detector hit")
    class DetectorHits {

        @Test
        @DisplayName("is unannotated PII when nobody declared the field, which is the signal to alert on")
        void unannotatedTextAlerts() {
            dataMask(MaskingPolicy.strict()).mask(new Unannotated("payment for " + IBAN));

            assertThat(recorder.unannotated).isNotEmpty();
            assertThat(recorder.scanned).isEmpty();
        }

        @Test
        @DisplayName("is only a scan when the field was declared free text, so it cannot dilute that alert")
        void declaredTextDoesNotAlert() {
            dataMask(MaskingPolicy.strict()).mask(new Note("payment for " + IBAN));

            assertThat(recorder.scanned).isNotEmpty();
            assertThat(recorder.unannotated).isEmpty();
        }

        @Test
        @DisplayName("still masks the value either way — the difference is the report, not the output")
        void bothStillMask() {
            Note masked = dataMask(MaskingPolicy.strict()).mask(new Note("payment for " + IBAN));

            assertThat(masked.text()).doesNotContain(IBAN);
        }
    }

    @Nested
    @DisplayName("A bound that stops the walk")
    class Bounds {

        @Test
        @DisplayName("reports a truncated list as truncation, against the container's own path")
        void listTruncation() {
            MaskingPolicy bounded = MaskingPolicy.strict().withMaxCollectionElements(3);

            dataMask(bounded)
                    .mask(new Bag(
                            IntStream.range(0, 10).mapToObj(Integer::toString).toList()));

            assertThat(recorder.truncated).containsExactly("Bag.values=3");
            assertThat(recorder.depth).isEmpty();
        }

        @Test
        @DisplayName("reports a truncated map the same way a list is reported, so the two can be counted together")
        void mapTruncationMatchesList() {
            MaskingPolicy bounded = MaskingPolicy.strict().withMaxCollectionElements(3);
            Map<String, String> entries = new LinkedHashMap<>();
            IntStream.range(0, 10).forEach(i -> entries.put("k" + i, "v" + i));

            dataMask(bounded).mask(new Keyed(entries));

            // Previously `Keyed.entries{3}` against the list's `Bag.values[3]` — same event, two
            // spellings, and neither of them the container's path.
            assertThat(recorder.truncated).containsExactly("Keyed.entries=3");
        }

        @Test
        @DisplayName("still reports a graph too deep as a depth limit, which is a different problem")
        void depthIsStillItsOwnSignal() {
            MaskingPolicy shallow = MaskingPolicy.strict().withMaxDepth(1);

            dataMask(shallow).mask(new Bag(List.of("a", "b")));

            assertThat(recorder.depth).isNotEmpty();
            assertThat(recorder.truncated).isEmpty();
        }
    }

    @Nested
    @DisplayName("The default observer")
    class Defaults {

        @Test
        @DisplayName("accepts the new signals without an implementation, so an existing observer still compiles")
        void newSignalsAreDefaulted() {
            MaskingObserver noop = MaskingObserver.NOOP;

            noop.onCollectionTruncated("anywhere", 3);
            noop.onScanned("anywhere", PiiCategory.EMAIL, "detector");
            noop.onMasked("anywhere", PiiCategory.EMAIL, MaskStrategy.REDACT);

            assertThat(noop).isNotNull();
        }
    }
}
