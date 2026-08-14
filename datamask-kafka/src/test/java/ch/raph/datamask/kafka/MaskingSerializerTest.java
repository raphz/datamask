package ch.raph.datamask.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.raph.datamask.application.DataMask;
import ch.raph.datamask.kafka.testdomain.Payments;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("A serializer that masks before it writes")
class MaskingSerializerTest {

    private static final String EMAIL = "john@example.com";
    private static final String IBAN = "CH9300762011623852957";

    private final DataMask dataMask = DataMask.withDefaults();

    @AfterEach
    void forgetTheInstalledInstance() {
        DataMaskKafka.uninstall();
    }

    private static String bytes(byte[] serialized) {
        return serialized == null ? null : new String(serialized, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("takes the PII out of a declared payload, so the bytes on the topic never held it")
    void masksADeclaredPayload() {
        Serializer<Payments.Payment> serializer = new MaskingSerializer<>(new ToStringSerializer<>(), dataMask);

        String written = bytes(serializer.serialize("payments", payment()));

        assertThat(written).doesNotContain(EMAIL).doesNotContain(IBAN).contains("CHF");
    }

    @Test
    @DisplayName("scans a payload that is plain text, because there is no declaration to read")
    void scansText() {
        Serializer<String> serializer = new MaskingSerializer<>(new StringSerializer(), dataMask);

        assertThat(bytes(serializer.serialize("payments", "settlement for " + IBAN)))
                .doesNotContain(IBAN)
                .startsWith("settlement for ");
    }

    @Test
    @DisplayName("serializes null as the delegate would, since there is nothing to mask")
    void passesNullThrough() {
        Serializer<String> serializer = new MaskingSerializer<>(new StringSerializer(), dataMask);

        assertThat(serializer.serialize("payments", null)).isNull();
    }

    @Test
    @DisplayName("fails the send when the payload cannot be masked, rather than writing it unmasked")
    void failsTheSend() {
        Serializer<Payments.Unrebuildable> serializer = new MaskingSerializer<>(new ToStringSerializer<>(), dataMask);

        assertThatThrownBy(() -> serializer.serialize("payments", new Payments.Unrebuildable(IBAN, "x", 1)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageNotContaining(IBAN);
    }

    @Test
    @DisplayName("works entirely from configuration, for a producer the application does not construct")
    void isConfigurableByName() {
        DataMaskKafka.install(dataMask);
        Serializer<String> serializer = new MaskingSerializer<>();
        serializer.configure(Map.of(DataMaskKafka.VALUE_DELEGATE_CONFIG, StringSerializer.class.getName()), false);

        assertThat(bytes(serializer.serialize("payments", "pay " + IBAN))).doesNotContain(IBAN);
    }

    @Test
    @DisplayName("refuses to start without something to delegate to, rather than defaulting to a pass-through")
    void refusesWithoutADelegate() {
        Serializer<String> serializer = new MaskingSerializer<>();

        assertThatThrownBy(() -> serializer.configure(Map.of(), false))
                .isInstanceOf(KafkaException.class)
                .hasMessageContaining(DataMaskKafka.VALUE_DELEGATE_CONFIG);
    }

    @Test
    @DisplayName("refuses to delegate to another masking serializer, which would mask twice and not terminate")
    void refusesToDelegateToItself() {
        Serializer<String> serializer = new MaskingSerializer<>();
        Map<String, Object> configs = Map.of(DataMaskKafka.VALUE_DELEGATE_CONFIG, MaskingSerializer.class.getName());

        assertThatThrownBy(() -> serializer.configure(configs, false))
                .isInstanceOf(KafkaException.class)
                .hasMessageContaining(MaskingSerializer.class.getName());
    }

    @Test
    @DisplayName("configures its delegate exactly once, whichever way the delegate arrived")
    void configuresTheDelegateOnce() {
        CountingSerializer delegate = new CountingSerializer();
        Serializer<String> fromConstructor = new MaskingSerializer<>(delegate, dataMask);
        fromConstructor.configure(Map.of(), false);

        assertThat(delegate.configured).isEqualTo(1);
    }

    @Test
    @DisplayName("masks the key when it is the key serializer, which is what configuring it there asks for")
    void masksTheKeyWhenItIsTheKeySerializer() {
        DataMaskKafka.install(dataMask);
        Serializer<String> serializer = new MaskingSerializer<>();
        serializer.configure(Map.of(DataMaskKafka.KEY_DELEGATE_CONFIG, StringSerializer.class.getName()), true);

        assertThat(bytes(serializer.serialize("payments", IBAN))).doesNotContain(IBAN);
    }

    @Test
    @DisplayName("picks up a DataMask installed after the producer was built, from the next record on")
    void picksUpALateInstall() {
        Serializer<String> serializer = new MaskingSerializer<>(new StringSerializer());

        // Masked already, under the ephemeral fallback, so the value is safe either way. What changes
        // is the pseudonym, which only becomes stable once the configured key is installed.
        String beforeInstall = bytes(serializer.serialize("payments", "ref " + IBAN));
        DataMaskKafka.install(DataMask.builder().secret("a-secret-long-enough").build());
        String afterInstall = bytes(serializer.serialize("payments", "ref " + IBAN));

        assertThat(beforeInstall).doesNotContain(IBAN);
        assertThat(afterInstall).doesNotContain(IBAN);
    }

    @Test
    @DisplayName("closes what it delegates to, so a delegate holding resources still releases them")
    void closesTheDelegate() {
        CountingSerializer delegate = new CountingSerializer();

        new MaskingSerializer<>(delegate, dataMask).close();

        assertThat(delegate.closed).isEqualTo(1);
    }

    private static Payments.Payment payment() {
        return new Payments.Payment(new Payments.Email(EMAIL), IBAN, "4111111111111111", "CHF", 4_250L);
    }

    /** Stands in for a JSON or Avro serializer: enough structure to assert on, no dependency to add. */
    private static final class ToStringSerializer<T> implements Serializer<T> {

        @Override
        public byte[] serialize(String topic, T data) {
            return data == null ? null : data.toString().getBytes(StandardCharsets.UTF_8);
        }
    }

    private static final class CountingSerializer implements Serializer<String> {

        private int configured;
        private int closed;

        @Override
        public void configure(Map<String, ?> configs, boolean isKey) {
            configured++;
        }

        @Override
        public byte[] serialize(String topic, String data) {
            return data == null ? null : data.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public void close() {
            closed++;
        }
    }
}
