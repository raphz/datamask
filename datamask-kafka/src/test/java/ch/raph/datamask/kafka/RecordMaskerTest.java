package ch.raph.datamask.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.raph.datamask.api.PiiCategory;
import ch.raph.datamask.application.DataMask;
import ch.raph.datamask.domain.MaskingObserver;
import ch.raph.datamask.domain.PiiDetector;
import ch.raph.datamask.domain.PiiFinding;
import ch.raph.datamask.kafka.testdomain.Payments;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("A record on its way to a topic")
class RecordMaskerTest {

    private static final String EMAIL = "john@example.com";
    private static final String IBAN = "CH9300762011623852957";
    private static final String CARD = "4111111111111111";

    private final DataMask dataMask = DataMask.withDefaults();
    private final RecordMasker masker = new RecordMasker(dataMask);

    private static Payments.Payment payment() {
        return new Payments.Payment(new Payments.Email(EMAIL), IBAN, CARD, "CHF", 4_250L);
    }

    private static <V> ProducerRecord<String, V> record(V value, Header... headers) {
        return new ProducerRecord<>("payments", null, null, "cust-4711", value, List.of(headers));
    }

    private static String headerValue(ProducerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null || header.value() == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    @Nested
    @DisplayName("the payload")
    class Payload {

        @Test
        @DisplayName("is masked from what the model declares, so the raw value never reaches the bytes")
        void masksDeclaredPii() {
            Payments.Payment masked = masker.mask(record(payment())).value();

            assertThat(masked.email().value()).doesNotContain(EMAIL).doesNotContain("john");
            assertThat(masked.iban()).doesNotContain(IBAN);
            assertThat(masked.card()).doesNotContain(CARD).endsWith("1111");
            assertThat(masked.currency()).isEqualTo("CHF");
            assertThat(masked.cents()).isEqualTo(4_250L);
        }

        @Test
        @DisplayName("is scanned when it is plain text, because there is no declaration to read")
        void scansText() {
            ProducerRecord<String, String> masked = masker.mask(record("settlement for " + IBAN));

            assertThat(masked.value()).doesNotContain(IBAN).startsWith("settlement for ");
        }

        @Test
        @DisplayName("keeps its own type, so the serializer downstream still accepts it")
        void keepsItsType() {
            assertThat(masker.mask(record(payment())).value()).isInstanceOf(Payments.Payment.class);
        }

        @Test
        @DisplayName("is left as the same object when it carried nothing, which is what keeps a clean record free")
        void returnsTheSameRecord() {
            ProducerRecord<String, String> clean = record("settlement 8842");

            assertThat(masker.mask(clean)).isSameAs(clean);
        }

        @Test
        @DisplayName("fails the send when it cannot be masked at all, rather than travelling unmasked")
        void throwsWhenItCannotBeRebuilt() {
            ProducerRecord<String, Payments.Unrebuildable> unrebuildable =
                    record(new Payments.Unrebuildable(IBAN, "x", 1));

            assertThatThrownBy(() -> masker.mask(unrebuildable))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageNotContaining(IBAN);
        }

        @Test
        @DisplayName("discloses nothing when its own masker is broken, because the engine fails closed first")
        void containsABrokenMasker() {
            Payments.Fragile masked =
                    masker.mask(record(new Payments.Fragile("swordfish"))).value();

            assertThat(masked.secret()).isEqualTo("****");
        }
    }

    @Nested
    @DisplayName("the key")
    class Key {

        @Test
        @DisplayName("is left alone by default, because masking it would change the partition and break compaction")
        void isNotMaskedByDefault() {
            ProducerRecord<String, Payments.Payment> masked = masker.mask(record(payment()));

            assertThat(masked.key()).isEqualTo("cust-4711");
        }

        @Test
        @DisplayName("is masked when the producer asked for it")
        void isMaskedWhenEnabled() {
            RecordMasker masksKeys = new RecordMasker(dataMask.engine(), true, Set.of());
            ProducerRecord<String, Payments.Payment> record =
                    new ProducerRecord<>("payments", null, null, "pay to " + IBAN, payment(), List.of());

            assertThat(masksKeys.mask(record).key()).doesNotContain(IBAN);
        }

        @Test
        @DisplayName("is masked by maskKey whatever the setting, since asking for it is the point of calling it")
        void isMaskedByTheDirectCall() {
            assertThat(masker.maskKey(IBAN, "payments")).doesNotContain(IBAN);
        }
    }

    @Nested
    @DisplayName("the headers")
    class Headers {

        @Test
        @DisplayName("are scanned, which is what covers the identifier nobody remembers is there")
        void scansHeaderValues() {
            ProducerRecord<String, String> masked =
                    masker.mask(record("ok", header("x-customer-email", EMAIL), header("x-debit", IBAN)));

            assertThat(headerValue(masked, "x-customer-email")).doesNotContain(EMAIL);
            assertThat(headerValue(masked, "x-debit")).doesNotContain(IBAN);
        }

        @Test
        @DisplayName("survive untouched when no detector recognises them, so tracing keeps working")
        void keepsWhatIsNotPii() {
            Header traceparent = header("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
            ProducerRecord<String, String> record = record("ok", traceparent);

            assertThat(masker.mask(record)).isSameAs(record);
        }

        @Test
        @DisplayName("are left alone when they are not UTF-8, because no detector can read them and binary "
                + "propagation has to survive")
        void keepsBinaryValues() {
            byte[] notUtf8 = {(byte) 0x8a, (byte) 0x1f, (byte) 0xc3};
            ProducerRecord<String, String> record = record("ok", new RecordHeader("b3", notUtf8));

            assertThat(masker.mask(record)).isSameAs(record);
        }

        @Test
        @DisplayName("are redacted wholesale when named in the configuration, which is the only way an opaque "
                + "internal identifier gets masked")
        void redactsByName() {
            RecordMasker redacting = new RecordMasker(dataMask.engine(), false, Set.of("X-Customer-Ref"));
            ProducerRecord<String, String> masked =
                    redacting.mask(record("ok", header("x-customer-ref", "4711"), header("x-tenant", "alpian")));

            assertThat(headerValue(masked, "x-customer-ref")).isEqualTo("****");
            assertThat(headerValue(masked, "x-tenant")).isEqualTo("alpian");
        }

        @Test
        @DisplayName("keep their order and their duplicate keys, which is why the record is rebuilt rather than "
                + "edited in place")
        void keepsOrderAndDuplicates() {
            ProducerRecord<String, String> masked = masker.mask(record(
                    "ok",
                    header("traceparent", "00-abc-def-01"),
                    header("x-debit", IBAN),
                    header("x-tenant", "alpian"),
                    header("x-debit", "CH5604835012345678009")));

            assertThat(masked.headers().toArray())
                    .extracting(Header::key)
                    .containsExactly("traceparent", "x-debit", "x-tenant", "x-debit");
            assertThat(masked.headers().headers("x-debit")).hasSize(2);
        }

        @Test
        @DisplayName("become the placeholder when masking one fails, rather than failing the send over a header")
        void failsClosedWithoutFailingTheSend() {
            DataMask broken = DataMask.builder().detector(new BrokenDetector()).build();
            RecordMasker failing = new RecordMasker(broken);

            // A payload with no text in it, so this test is about the header and nothing else: a broken
            // scan over the *value* is a different rule, and fails the send.
            ProducerRecord<String, Long> masked = failing.mask(record(4_250L, header("x-debit", IBAN)));

            assertThat(headerValue(masked, "x-debit")).isEqualTo("****").doesNotContain(IBAN);
        }

        @Test
        @DisplayName("are reported to the observer by a path that names the topic and the header")
        void reportsWhereTheValueCameFrom() {
            List<String> paths = new ArrayList<>();
            DataMask observed = DataMask.builder()
                    .observer(new MaskingObserver() {
                        @Override
                        public void onUnannotatedPii(String path, PiiCategory category, String detector) {
                            paths.add(path);
                        }
                    })
                    .build();

            new RecordMasker(observed).mask(record("ok", header("x-debit", IBAN)));

            assertThat(paths).contains("kafka:header/payments/x-debit");
        }

        private Header header(String name, String value) {
            return new RecordHeader(name, value.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** A detector that throws, to prove a broken scan discloses nothing and does not fail the send. */
    private static final class BrokenDetector implements PiiDetector {

        @Override
        public String name() {
            return "broken";
        }

        @Override
        public List<PiiFinding> detect(CharSequence text) {
            throw new IllegalStateException("this detector is broken");
        }
    }
}
