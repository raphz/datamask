package ch.raph.datamask.kafka;

import ch.raph.datamask.application.DataMask;
import ch.raph.datamask.application.MaskingEngine;
import java.util.Map;
import java.util.Objects;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serializer;

/**
 * A {@link Serde} that masks on the way out and reads on the way in, for Kafka Streams.
 *
 * {@snippet :
 * Serde<Payment> payments = new MaskingSerde<>(new JsonSerde<>(Payment.class), dataMask);
 *
 * builder.stream("payments", Consumed.with(Serdes.String(), new JsonSerde<>(Payment.class)))
 *         .filter(this::isSettled)
 *         .to("settled-payments", Produced.with(Serdes.String(), payments));
 * }
 *
 * <p>Streams asks for a serde where a plain producer asks for a serializer, so a topology that wants
 * {@link MaskingSerializer} has nowhere to put it. This is that place: the serializer is a
 * {@code MaskingSerializer} around the delegate's, and the deserializer <b>is</b> the delegate's, not
 * a wrapper of it.
 *
 * <h2>Reading is deliberately a pass-through</h2>
 *
 * This module protects what a process writes. A topology masking on the way in would be masking the
 * input its own operators are about to work on — the join key, the amount, the field it branches on —
 * and then writing the result of arithmetic on placeholders. Where a stream really is handling records
 * whose PII it does not need, {@link MaskingConsumerInterceptor} is the honest way to say so, because
 * it says it once for the whole application rather than field by field.
 *
 * <p>The consequence worth knowing: in a {@code Materialized} store this serde writes masked and reads
 * back masked, so what the store holds is what the topic would have held. That is the point, not a
 * gap — a state store is a changelog topic, with the same retention and the same readers.
 *
 * <h2>Where it belongs in a topology</h2>
 *
 * {@code Produced.with(...)} and {@code Materialized.with(...)}, on the slot whose records leave this
 * process. As a {@code default.value.serde} it would also mask everything written to every internal
 * repartition topic, which is safe but masks values the topology then rejoins on — so name it where it
 * is meant rather than as the default.
 *
 * <p>Masking keys follows {@link MaskingSerializer}: this serde masks whichever slot it sits in only
 * once Streams has called {@link #configure(Map, boolean)} to say which that is, and a serde handed
 * straight to {@code Produced.with} is never configured. For a key that has to be masked, mask it in
 * the topology — a {@code selectKey} through {@code DataMask#pseudonymize} keeps distinct keys
 * distinct, which is what repartitioning and compaction need.
 */
public final class MaskingSerde<T> implements Serde<T> {

    private final MaskingSerializer<T> serializer;
    private final Deserializer<T> deserializer;

    /** Masks with whatever {@link DataMaskKafka#install} was given, resolved per record. */
    public MaskingSerde(Serde<T> delegate) {
        Objects.requireNonNull(delegate, "delegate");
        this.serializer = new MaskingSerializer<>(delegate.serializer());
        this.deserializer = delegate.deserializer();
    }

    public MaskingSerde(Serde<T> delegate, DataMask dataMask) {
        this(delegate, Objects.requireNonNull(dataMask, "dataMask").engine());
    }

    public MaskingSerde(Serde<T> delegate, MaskingEngine engine) {
        Objects.requireNonNull(delegate, "delegate");
        this.serializer = new MaskingSerializer<>(delegate.serializer(), engine);
        this.deserializer = delegate.deserializer();
    }

    /**
     * @param serializer what the delegate serializes with once this has masked
     * @param deserializer the delegate's own, used as it is
     */
    public MaskingSerde(Serializer<T> serializer, Deserializer<T> deserializer, MaskingEngine engine) {
        this.serializer = new MaskingSerializer<>(serializer, engine);
        this.deserializer = Objects.requireNonNull(deserializer, "deserializer");
    }

    @Override
    public Serializer<T> serializer() {
        return serializer;
    }

    /** The delegate's own deserializer. Reading is not masked; see the class documentation. */
    @Override
    public Deserializer<T> deserializer() {
        return deserializer;
    }

    /**
     * Passed on to both, so a delegate that reads its own settings out of the Streams configuration —
     * a schema registry URL, a target type — still gets them. {@code isKey} is what tells the
     * serializer which slot it is in.
     */
    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {
        serializer.configure(configs, isKey);
        deserializer.configure(configs, isKey);
    }

    @Override
    public void close() {
        // MaskingSerializer closes what it delegates to, so this closes the delegate serde's halves
        // exactly once each.
        serializer.close();
        deserializer.close();
    }
}
