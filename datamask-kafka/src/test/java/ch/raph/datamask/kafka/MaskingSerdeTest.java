package ch.raph.datamask.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import ch.raph.datamask.application.DataMask;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.Serializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("A serde for a topology that writes")
class MaskingSerdeTest {

    private static final String IBAN = "CH9300762011623852957";

    private final DataMask dataMask = DataMask.withDefaults();

    @AfterEach
    void forgetTheInstalledInstance() {
        DataMaskKafka.uninstall();
    }

    @Test
    @DisplayName("masks what the topology writes, because that is the half of a serde that leaves the process")
    void masksOnTheWayOut() {
        Serde<String> serde = new MaskingSerde<>(Serdes.String(), dataMask);

        String written = text(serde.serializer().serialize("payments", "settlement for " + IBAN));

        assertThat(written).doesNotContain(IBAN).startsWith("settlement for ");
    }

    @Test
    @DisplayName("reads with the delegate's own deserializer, so an operator still sees what the topic holds")
    void readsThroughUntouched() {
        Serde<String> delegate = Serdes.String();
        Serde<String> serde = new MaskingSerde<>(delegate, dataMask);

        assertThat(serde.deserializer()).isSameAs(delegate.deserializer());
        assertThat(serde.deserializer().deserialize("payments", IBAN.getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(IBAN);
    }

    @Test
    @DisplayName("masks with whatever was installed when it was built without a DataMask of its own")
    void usesTheInstalledInstance() {
        DataMaskKafka.install(dataMask);
        Serde<String> serde = new MaskingSerde<>(Serdes.String());

        assertThat(text(serde.serializer().serialize("payments", "pay " + IBAN)))
                .doesNotContain(IBAN);
    }

    @Test
    @DisplayName("configures both halves, so a delegate that reads its own settings out of the topology's "
            + "configuration still gets them")
    void configuresBothHalves() {
        Counting delegate = new Counting();
        Serde<String> serde = new MaskingSerde<>(delegate, dataMask);

        serde.configure(Map.of(), false);

        assertThat(delegate.serializer.configured).isEqualTo(1);
        assertThat(delegate.deserializer.configured).isEqualTo(1);
    }

    @Test
    @DisplayName("closes both halves, so a delegate holding resources still releases them")
    void closesBothHalves() {
        Counting delegate = new Counting();

        new MaskingSerde<>(delegate, dataMask).close();

        assertThat(delegate.serializer.closed).isEqualTo(1);
        assertThat(delegate.deserializer.closed).isEqualTo(1);
    }

    @Test
    @DisplayName("masks the key slot once the topology says which slot it is, the same rule the serializer follows")
    void masksTheKeySlotWhenConfigured() {
        DataMaskKafka.install(dataMask);
        Serde<String> serde = new MaskingSerde<>(Serdes.String());

        serde.configure(Map.of(), true);

        assertThat(text(serde.serializer().serialize("payments", IBAN))).doesNotContain(IBAN);
    }

    private static String text(byte[] serialized) {
        return serialized == null ? null : new String(serialized, StandardCharsets.UTF_8);
    }

    /** A delegate serde that counts what was done to each half. */
    private static final class Counting implements Serde<String> {

        private final CountingSerializer serializer = new CountingSerializer();
        private final CountingDeserializer deserializer = new CountingDeserializer();

        @Override
        public Serializer<String> serializer() {
            return serializer;
        }

        @Override
        public Deserializer<String> deserializer() {
            return deserializer;
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

    private static final class CountingDeserializer implements Deserializer<String> {

        private int configured;
        private int closed;

        @Override
        public void configure(Map<String, ?> configs, boolean isKey) {
            configured++;
        }

        @Override
        public String deserialize(String topic, byte[] data) {
            return data == null ? null : new String(data, StandardCharsets.UTF_8);
        }

        @Override
        public void close() {
            closed++;
        }
    }
}
