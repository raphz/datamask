package ch.raph.datamask.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import ch.raph.datamask.application.DataMask;
import ch.raph.datamask.domain.MaskingObserver;
import ch.raph.datamask.kafka.testdomain.Payments;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.kafka.clients.consumer.ConsumerInterceptor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Calling {@code onConsume} is the interceptor's actual contract, the same way the producer side is
 * tested by calling {@code onSend}. {@code MaskingKafkaTest} is what proves a real
 * {@code KafkaConsumer} goes through it at all.
 */
@DisplayName("An interceptor that masks every record a consumer polls")
class MaskingConsumerInterceptorTest {

    private static final String EMAIL = "john@example.com";
    private static final String IBAN = "CH9300762011623852957";
    private static final TopicPartition PARTITION = new TopicPartition("payments", 0);

    private final DataMask dataMask = DataMask.withDefaults();

    @AfterEach
    void forgetTheInstalledInstance() {
        DataMaskKafka.uninstall();
    }

    @Test
    @DisplayName("masks the payload and the headers of what the application is about to be handed")
    void masksTheWholeRecord() {
        ConsumerInterceptor<String, Payments.Payment> interceptor = new MaskingConsumerInterceptor<>(dataMask);

        ConsumerRecord<String, Payments.Payment> masked = only(
                interceptor.onConsume(batch(record(11L, payment(), header("x-customer-email", EMAIL)))));

        assertThat(masked.value().iban()).doesNotContain(IBAN);
        assertThat(headerValue(masked, "x-customer-email")).doesNotContain(EMAIL);
    }

    @Test
    @DisplayName("keeps everything that says where the record came from, so offsets and tracing still line up")
    void keepsPositionAndMetadata() {
        ConsumerInterceptor<String, Payments.Payment> interceptor = new MaskingConsumerInterceptor<>(dataMask);

        ConsumerRecord<String, Payments.Payment> masked = only(interceptor.onConsume(batch(record(11L, payment()))));

        assertThat(masked.topic()).isEqualTo("payments");
        assertThat(masked.partition()).isZero();
        assertThat(masked.offset()).isEqualTo(11L);
        assertThat(masked.timestamp()).isEqualTo(1_700_000_000_000L);
        assertThat(masked.timestampType()).isEqualTo(TimestampType.CREATE_TIME);
        assertThat(masked.leaderEpoch()).contains(7);
        // The sizes describe the bytes that were received, and stay true of them.
        assertThat(masked.serializedKeySize()).isEqualTo(12);
        assertThat(masked.serializedValueSize()).isEqualTo(34);
    }

    @Test
    @DisplayName("hands back the same batch when nothing in it carried PII, which is a topic a masking producer "
            + "already wrote")
    void forwardsACleanBatch() {
        ConsumerInterceptor<String, String> interceptor = new MaskingConsumerInterceptor<>(dataMask);
        ConsumerRecords<String, String> clean = batch(record(11L, "settlement 8842"));

        assertThat(interceptor.onConsume(clean)).isSameAs(clean);
    }

    @Test
    @DisplayName("leaves an empty poll alone, which is most of them")
    void forwardsAnEmptyPoll() {
        ConsumerInterceptor<String, String> interceptor = new MaskingConsumerInterceptor<>(dataMask);
        ConsumerRecords<String, String> empty = ConsumerRecords.empty();

        assertThat(interceptor.onConsume(empty)).isSameAs(empty);
    }

    @Test
    @DisplayName("leaves the key alone until the consumer configures it, because application code groups and "
            + "deduplicates by it")
    void masksTheKeyOnlyWhenConfigured() {
        DataMaskKafka.install(dataMask);
        ConsumerRecords<String, String> polled = batch(record(11L, "ok", "pay to " + IBAN));

        ConsumerInterceptor<String, String> off = configured(Map.of());
        ConsumerInterceptor<String, String> on = configured(Map.of(DataMaskKafka.MASK_KEYS_CONFIG, "true"));

        assertThat(only(off.onConsume(polled)).key()).isEqualTo("pay to " + IBAN);
        assertThat(only(on.onConsume(polled)).key()).doesNotContain(IBAN);
    }

    @Test
    @DisplayName("redacts the headers the consumer named, the same configuration the producer side reads")
    void readsTheRedactedHeaderList() {
        DataMaskKafka.install(dataMask);
        ConsumerInterceptor<String, String> interceptor =
                configured(Map.of(DataMaskKafka.REDACTED_HEADERS_CONFIG, "x-customer-ref"));

        ConsumerRecord<String, String> masked = only(interceptor.onConsume(
                batch(record(11L, "ok", header("x-customer-ref", "4711"), header("x-tenant", "alpian")))));

        assertThat(headerValue(masked, "x-customer-ref")).isEqualTo("****");
        assertThat(headerValue(masked, "x-tenant")).isEqualTo("alpian");
    }

    @Test
    @DisplayName("drops a record it could not mask and delivers the rest, because Kafka answers a thrown "
            + "exception by delivering the records it had before — the unmasked ones")
    void dropsWhatItCannotMask() {
        ConsumerInterceptor<String, Object> interceptor = new MaskingConsumerInterceptor<>(dataMask);
        ConsumerRecords<String, Object> polled = batch(
                record(11L, "settlement for " + IBAN),
                record(12L, new Payments.Unrebuildable(IBAN, "x", 1)),
                record(13L, "settlement 8842"));

        ConsumerRecords<String, Object> delivered = interceptor.onConsume(polled);

        assertThat(delivered.count()).isEqualTo(2);
        assertThat(delivered).extracting(ConsumerRecord::offset).containsExactly(11L, 13L);
        assertThat(delivered)
                .allSatisfy(record -> assertThat(String.valueOf(record.value())).doesNotContain(IBAN));
    }

    @Test
    @DisplayName("reports a dropped record to the observer, so a metrics sink learns about it rather than only a "
            + "log file nobody alerts on")
    void reportsADroppedRecordToTheObserver() {
        List<String> failures = new ArrayList<>();
        DataMask observed = DataMask.builder()
                .observer(new MaskingObserver() {
                    @Override
                    public void onFailure(String path, Throwable error) {
                        failures.add(path);
                    }
                })
                .build();
        ConsumerInterceptor<String, Payments.Unrebuildable> interceptor = new MaskingConsumerInterceptor<>(observed);

        interceptor.onConsume(batch(record(11L, new Payments.Unrebuildable(IBAN, "x", 1))));

        assertThat(failures).containsExactly("kafka:record/payments");
    }

    @Test
    @DisplayName("still drops the record when the observer itself throws, because a thrown onConsume would deliver "
            + "the unmasked batch instead")
    void survivesAnObserverThatThrows() {
        DataMask observed = DataMask.builder()
                .observer(new MaskingObserver() {
                    @Override
                    public void onFailure(String path, Throwable error) {
                        throw new IllegalStateException("this observer is broken");
                    }
                })
                .build();
        ConsumerInterceptor<String, Payments.Unrebuildable> interceptor = new MaskingConsumerInterceptor<>(observed);

        ConsumerRecords<String, Payments.Unrebuildable> delivered =
                interceptor.onConsume(batch(record(11L, new Payments.Unrebuildable(IBAN, "x", 1))));

        assertThat(delivered.count()).isZero();
    }

    @Test
    @DisplayName("uses the DataMask installed for it when Kafka built it from a class name")
    void usesTheInstalledInstance() {
        DataMaskKafka.install(dataMask);
        ConsumerInterceptor<String, Payments.Payment> interceptor = configured(Map.of());

        assertThat(only(interceptor.onConsume(batch(record(11L, payment())))).value().iban())
                .doesNotContain(IBAN);
    }

    @Test
    @DisplayName("still masks when nothing was installed, under an ephemeral key rather than not at all")
    void fallsBackToStrictMasking() {
        ConsumerInterceptor<String, Payments.Payment> interceptor = configured(Map.of());

        assertThat(only(interceptor.onConsume(batch(record(11L, payment())))).value().iban())
                .doesNotContain(IBAN);
    }

    @Test
    @DisplayName("has nothing to say about committed offsets, which are the consumer's own bookkeeping")
    void leavesOffsetsAlone() {
        ConsumerInterceptor<String, String> interceptor = new MaskingConsumerInterceptor<>(dataMask);

        interceptor.onCommit(Map.of());
        interceptor.close();
    }

    private static <K, V> ConsumerInterceptor<K, V> configured(Map<String, ?> configs) {
        ConsumerInterceptor<K, V> interceptor = new MaskingConsumerInterceptor<>();
        interceptor.configure(configs);
        return interceptor;
    }

    private static Payments.Payment payment() {
        return new Payments.Payment(new Payments.Email(EMAIL), IBAN, "4111111111111111", "CHF", 4_250L);
    }

    private static <V> ConsumerRecord<String, V> record(long offset, V value, Header... headers) {
        return record(offset, value, "cust-4711", headers);
    }

    /** Everything a polled record carries, so a test can assert that none of it was lost on the way. */
    private static <V> ConsumerRecord<String, V> record(long offset, V value, String key, Header... headers) {
        return new ConsumerRecord<>(
                "payments",
                0,
                offset,
                1_700_000_000_000L,
                TimestampType.CREATE_TIME,
                12,
                34,
                key,
                value,
                new RecordHeaders(headers),
                Optional.of(7),
                Optional.empty());
    }

    // Safe, and the suppression is for passing the array straight on to List.of: nothing here writes
    // to it.
    @SafeVarargs
    @SuppressWarnings("varargs")
    private static <V> ConsumerRecords<String, V> batch(ConsumerRecord<String, V>... records) {
        Map<TopicPartition, List<ConsumerRecord<String, V>>> byPartition = new LinkedHashMap<>();
        byPartition.put(PARTITION, List.of(records));
        return new ConsumerRecords<>(byPartition, Map.of());
    }

    private static <V> ConsumerRecord<String, V> only(ConsumerRecords<String, V> records) {
        assertThat(records.count()).isEqualTo(1);
        return records.iterator().next();
    }

    private static Header header(String name, String value) {
        return new RecordHeader(name, value.getBytes(StandardCharsets.UTF_8));
    }

    private static String headerValue(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null || header.value() == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
