package ch.raph.datamask.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import ch.raph.datamask.application.DataMask;
import ch.raph.datamask.domain.MaskingObserver;
import ch.raph.datamask.kafka.testdomain.Payments;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code MockProducer} is not used here, and could not be: it stores the {@code ProducerRecord}
 * objects it was handed rather than the bytes, and it never runs the interceptor chain. Calling
 * {@code onSend} is the interceptor's actual contract, and {@code MaskingKafkaTest} is what proves the
 * producer really goes through it.
 */
@DisplayName("An interceptor that masks every record a producer sends")
class MaskingProducerInterceptorTest {

    private static final String EMAIL = "john@example.com";
    private static final String IBAN = "CH9300762011623852957";

    private final DataMask dataMask = DataMask.withDefaults();

    @AfterEach
    void forgetTheInstalledInstance() {
        DataMaskKafka.uninstall();
    }

    private static <V> ProducerRecord<String, V> record(V value, Header... headers) {
        return new ProducerRecord<>("payments", null, null, "cust-4711", value, List.of(headers));
    }

    private static Header header(String name, String value) {
        return new RecordHeader(name, value.getBytes(StandardCharsets.UTF_8));
    }

    private static String headerValue(ProducerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null || header.value() == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static Payments.Payment payment() {
        return new Payments.Payment(new Payments.Email(EMAIL), IBAN, "4111111111111111", "CHF", 4_250L);
    }

    @Test
    @DisplayName("masks the payload and the headers together, which is the reason to prefer it to a serializer")
    void masksTheWholeRecord() {
        ProducerInterceptor<String, Payments.Payment> interceptor = new MaskingProducerInterceptor<>(dataMask);

        ProducerRecord<String, Payments.Payment> masked =
                interceptor.onSend(record(payment(), header("x-customer-email", EMAIL)));

        assertThat(masked.value().iban()).doesNotContain(IBAN);
        assertThat(headerValue(masked, "x-customer-email")).doesNotContain(EMAIL);
    }

    @Test
    @DisplayName("keeps the topic, the partition and the timestamp the caller chose")
    void keepsRoutingAndTimestamp() {
        ProducerInterceptor<String, Payments.Payment> interceptor = new MaskingProducerInterceptor<>(dataMask);
        ProducerRecord<String, Payments.Payment> original =
                new ProducerRecord<>("payments", 3, 1_700_000_000_000L, "cust-4711", payment(), List.of());

        ProducerRecord<String, Payments.Payment> masked = interceptor.onSend(original);

        assertThat(masked.topic()).isEqualTo("payments");
        assertThat(masked.partition()).isEqualTo(3);
        assertThat(masked.timestamp()).isEqualTo(1_700_000_000_000L);
        assertThat(masked.key()).isEqualTo("cust-4711");
    }

    @Test
    @DisplayName("forwards the same record when it carried nothing")
    void forwardsACleanRecord() {
        ProducerInterceptor<String, String> interceptor = new MaskingProducerInterceptor<>(dataMask);
        ProducerRecord<String, String> clean = record("settlement 8842");

        assertThat(interceptor.onSend(clean)).isSameAs(clean);
    }

    @Test
    @DisplayName("leaves the key alone until the producer configures it, because a masked key repartitions")
    void masksTheKeyOnlyWhenConfigured() {
        DataMaskKafka.install(dataMask);
        ProducerRecord<String, String> original =
                new ProducerRecord<>("payments", null, null, "pay to " + IBAN, "ok", List.of());

        ProducerInterceptor<String, String> off = configured(Map.of());
        ProducerInterceptor<String, String> on = configured(Map.of(DataMaskKafka.MASK_KEYS_CONFIG, "true"));

        assertThat(off.onSend(original).key()).isEqualTo("pay to " + IBAN);
        assertThat(on.onSend(original).key()).doesNotContain(IBAN);
    }

    @Test
    @DisplayName("redacts the headers the producer named, comma-separated as Kafka's own list configs are")
    void readsTheRedactedHeaderList() {
        DataMaskKafka.install(dataMask);
        ProducerInterceptor<String, String> interceptor =
                configured(Map.of(DataMaskKafka.REDACTED_HEADERS_CONFIG, "x-customer-ref, X-Account"));

        ProducerRecord<String, String> masked = interceptor.onSend(
                record("ok", header("x-customer-ref", "4711"), header("x-account", "0815"), header("x-tenant", "a")));

        assertThat(headerValue(masked, "x-customer-ref")).isEqualTo("****");
        assertThat(headerValue(masked, "x-account")).isEqualTo("****");
        assertThat(headerValue(masked, "x-tenant")).isEqualTo("a");
    }

    @Test
    @DisplayName("drops a record it could not mask instead of throwing, because Kafka answers a thrown "
            + "exception by publishing the record it had before — the unmasked one")
    void dropsWhatItCannotMask() {
        ProducerInterceptor<String, Payments.Unrebuildable> interceptor = new MaskingProducerInterceptor<>(dataMask);

        ProducerRecord<String, Payments.Unrebuildable> masked =
                interceptor.onSend(record(new Payments.Unrebuildable(IBAN, "x", 1)));

        assertThat(masked).isNull();
    }

    @Test
    @DisplayName("reports a dropped record to the observer, so metrics and an audit sink learn about it rather "
            + "than only a log file nobody has an alert on")
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
        ProducerInterceptor<String, Payments.Unrebuildable> interceptor = new MaskingProducerInterceptor<>(observed);

        ProducerRecord<String, Payments.Unrebuildable> dropped =
                interceptor.onSend(record(new Payments.Unrebuildable(IBAN, "x", 1)));

        assertThat(dropped).isNull();
        // The engine reports the structural failure under its own root path first; what this
        // pins is the second one, which names the record the interceptor then dropped.
        assertThat(failures).contains("kafka:record/payments");
    }

    @Test
    @DisplayName("still drops the record when the observer itself throws, because a thrown onSend has Kafka "
            + "publish the record it had before — the unmasked one")
    void survivesAnObserverThatThrows() {
        DataMask observed = DataMask.builder()
                .observer(new MaskingObserver() {
                    @Override
                    public void onFailure(String path, Throwable error) {
                        throw new IllegalStateException("this observer is broken");
                    }
                })
                .build();
        ProducerInterceptor<String, Payments.Unrebuildable> interceptor = new MaskingProducerInterceptor<>(observed);

        assertThat(interceptor.onSend(record(new Payments.Unrebuildable(IBAN, "x", 1))))
                .isNull();
    }

    @Test
    @DisplayName("uses the DataMask installed for it when Kafka built it from a class name")
    void usesTheInstalledInstance() {
        DataMaskKafka.install(dataMask);
        ProducerInterceptor<String, Payments.Payment> interceptor = configured(Map.of());

        assertThat(interceptor.onSend(record(payment())).value().iban()).doesNotContain(IBAN);
    }

    @Test
    @DisplayName("still masks when nothing was installed, under an ephemeral key rather than not at all")
    void fallsBackToStrictMasking() {
        ProducerInterceptor<String, Payments.Payment> interceptor = configured(Map.of());

        assertThat(interceptor.onSend(record(payment())).value().iban()).doesNotContain(IBAN);
    }

    private static <K, V> ProducerInterceptor<K, V> configured(Map<String, ?> configs) {
        ProducerInterceptor<K, V> interceptor = new MaskingProducerInterceptor<>();
        interceptor.configure(configs);
        return interceptor;
    }
}
