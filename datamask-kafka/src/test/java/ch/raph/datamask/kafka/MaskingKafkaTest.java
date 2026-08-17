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
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
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
 * <p>Every other test in this module calls the plugin directly. This one does not assume the client
 * goes through them at all — it configures a {@code KafkaProducer} and a {@code KafkaConsumer} the way
 * an application would, which is the only way to know that the serializer is reached, that the
 * interceptors are reached, and that none of them is bypassed by something in between.
 *
 * <p>{@link #leaksWithoutTheModule()} is deliberately the first assertion: a test that a value is
 * absent proves nothing unless the same produce demonstrably leaks it without the module in place. It
 * is also what the consumer-side test consumes from: a topic written before any of this was installed
 * is exactly the case {@link MaskingConsumerInterceptor} exists for.
 */
@DisplayName("A Kafka client against a real broker")
class MaskingKafkaTest {

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

    @Test
    @DisplayName("masks what a consumer polls from a topic that was written unmasked, which is every topic with "
            + "history behind it")
    void theConsumerInterceptorMasksWhatWasAlreadyThere() {
        String topic = topic();
        DataMaskKafka.install(DataMask.withDefaults());

        // Written without the module, so the topic itself still holds the raw value — proven by
        // leaksWithoutTheModule above, which produces exactly this way.
        produce(topic, Map.of());

        ConsumerRecord<String, String> polled = consumeAsText(
                topic,
                Map.of(
                        ConsumerConfig.INTERCEPTOR_CLASSES_CONFIG,
                        MaskingConsumerInterceptor.class.getName(),
                        DataMaskKafka.REDACTED_HEADERS_CONFIG,
                        "x-customer-ref"));

        assertThat(polled.value()).doesNotContain(IBAN).startsWith("settlement for ");
        assertThat(header(polled, "x-customer-email")).doesNotContain(EMAIL);
        assertThat(header(polled, "x-customer-ref")).isEqualTo("****");
        assertThat(header(polled, "traceparent")).isEqualTo("00-abc-def-01");
        // Application code correlates by the key, so it is left alone until a consumer asks otherwise.
        assertThat(polled.key()).isEqualTo("cust-4711");
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
        return new Landed(poll(topic, ByteArrayDeserializer.class, Map.of()));
    }

    /**
     * Read back as text, which is what an application configures and therefore what the consumer
     * interceptor is handed: by the time it runs, the value has already been deserialized.
     */
    private static ConsumerRecord<String, String> consumeAsText(String topic, Map<String, String> extra) {
        return poll(topic, StringDeserializer.class, extra);
    }

    private static <T> ConsumerRecord<T, T> poll(
            String topic, Class<? extends Deserializer<T>> deserializer, Map<String, String> extra) {
        Map<String, Object> configs = new HashMap<>();
        configs.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        configs.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        configs.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        configs.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, deserializer.getName());
        configs.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, deserializer.getName());
        configs.putAll(extra);

        try (KafkaConsumer<T, T> consumer = new KafkaConsumer<>(configs)) {
            consumer.subscribe(List.of(topic));
            ConsumerRecords<T, T> polled = consumer.poll(Duration.ofSeconds(30));
            assertThat(polled.count()).as("nothing was published to %s", topic).isEqualTo(1);
            return polled.iterator().next();
        }
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header found = record.headers().lastHeader(name);
        return found == null || found.value() == null ? null : new String(found.value(), StandardCharsets.UTF_8);
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
