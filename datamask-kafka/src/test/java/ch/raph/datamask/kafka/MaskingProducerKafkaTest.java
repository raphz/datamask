package ch.raph.datamask.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ch.raph.datamask.application.DataMask;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.kafka.KafkaContainer;

/**
 * The end-to-end case: a real broker, a real produce, and the bytes that actually landed on the topic
 * read back as bytes.
 *
 * <p>Every other test in this module calls the plugin directly. This one does not assume the producer
 * goes through them at all — it configures a {@code KafkaProducer} the way an application would and
 * checks what a consumer sees, which is the only way to know that the serializer is reached, that the
 * interceptor is reached, and that neither is bypassed by something in between.
 *
 * <p>{@link #leaksWithoutTheModule()} is deliberately the first assertion: a test that a value is
 * absent proves nothing unless the same produce demonstrably leaks it without the module in place.
 */
@DisplayName("A producer against a real Kafka broker")
class MaskingProducerKafkaTest {

    private static final String EMAIL = "john@example.com";
    private static final String IBAN = "CH9300762011623852957";
    private static final String PAYLOAD = "settlement for " + IBAN;

    private static KafkaContainer kafka;

    @BeforeAll
    static void startBroker() {
        // A contributor without Docker still gets every other test in the module; CI has Docker, so
        // this one runs there on every build.
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");

        kafka = new KafkaContainer("apache/kafka:4.2.1");
        kafka.start();
    }

    @AfterAll
    static void stopBroker() {
        if (kafka != null) {
            kafka.stop();
        }
    }

    @AfterEach
    void forgetTheInstalledInstance() {
        DataMaskKafka.uninstall();
    }

    @Test
    @DisplayName("leaks the payload and the header verbatim without the module, which is what makes the rest "
            + "of this class worth asserting")
    void leaksWithoutTheModule() {
        String topic = topic();

        produce(topic, Map.of());

        Landed landed = consume(topic);
        assertThat(landed.value()).contains(IBAN);
        assertThat(landed.header("x-customer-email")).isEqualTo(EMAIL);
    }

    @Test
    @DisplayName("writes a masked payload when the value serializer is swapped, and leaves the headers to the "
            + "interceptor")
    void theSerializerMasksThePayload() {
        String topic = topic();
        DataMaskKafka.install(DataMask.withDefaults());

        produce(
                topic,
                Map.of(
                        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, MaskingSerializer.class.getName(),
                        DataMaskKafka.VALUE_DELEGATE_CONFIG, StringSerializer.class.getName()));

        Landed landed = consume(topic);
        assertThat(landed.value()).doesNotContain(IBAN).startsWith("settlement for ");
        // The documented gap: Headers has no in-place set, so a serializer cannot rewrite one without
        // reordering the list. Add the interceptor for those.
        assertThat(landed.header("x-customer-email")).isEqualTo(EMAIL);
    }

    @Test
    @DisplayName("masks the payload and the headers when the interceptor is configured, without touching the "
            + "serializers at all")
    void theInterceptorMasksEverything() {
        String topic = topic();
        DataMaskKafka.install(DataMask.withDefaults());

        produce(
                topic,
                Map.of(
                        ProducerConfig.INTERCEPTOR_CLASSES_CONFIG,
                        MaskingProducerInterceptor.class.getName(),
                        DataMaskKafka.REDACTED_HEADERS_CONFIG,
                        "x-customer-ref"));

        Landed landed = consume(topic);
        assertThat(landed.value()).doesNotContain(IBAN);
        assertThat(landed.header("x-customer-email")).doesNotContain(EMAIL);
        assertThat(landed.header("x-customer-ref")).isEqualTo("****");
        // Recognised by no detector, so it travels intact and tracing keeps working.
        assertThat(landed.header("traceparent")).isEqualTo("00-abc-def-01");
        // The key decides the partition, so it is left alone until a producer asks otherwise.
        assertThat(landed.key()).isEqualTo("cust-4711");
    }

    private static String topic() {
        return "payments-" + UUID.randomUUID();
    }

    private static void produce(String topic, Map<String, String> extra) {
        Map<String, Object> configs = new HashMap<>();
        configs.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        configs.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        configs.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        configs.putAll(extra);

        List<Header> headers = List.of(
                new RecordHeader("traceparent", "00-abc-def-01".getBytes(StandardCharsets.UTF_8)),
                new RecordHeader("x-customer-email", EMAIL.getBytes(StandardCharsets.UTF_8)),
                new RecordHeader("x-customer-ref", "4711".getBytes(StandardCharsets.UTF_8)));

        try (Producer<String, String> producer = new KafkaProducer<>(configs)) {
            producer.send(new ProducerRecord<>(topic, null, null, "cust-4711", PAYLOAD, headers));
            producer.flush();
        }
    }

    /** Read back as bytes, so the assertions are on exactly what the topic holds. */
    private static Landed consume(String topic) {
        Map<String, Object> configs = new HashMap<>();
        configs.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        configs.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        configs.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        configs.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        configs.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());

        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(configs)) {
            consumer.subscribe(List.of(topic));
            ConsumerRecords<byte[], byte[]> polled = consumer.poll(Duration.ofSeconds(30));
            assertThat(polled.count()).as("nothing was published to %s", topic).isEqualTo(1);
            return new Landed(polled.iterator().next());
        }
    }

    /** What a consumer sees, rendered as text only at the point of asserting on it. */
    private record Landed(ConsumerRecord<byte[], byte[]> record) {

        String key() {
            return text(record.key());
        }

        String value() {
            return text(record.value());
        }

        String header(String name) {
            Header header = record.headers().lastHeader(name);
            return header == null ? null : text(header.value());
        }

        private static String text(byte[] bytes) {
            return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
        }
    }
}
