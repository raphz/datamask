package ch.raph.datamask.kafka;

import ch.raph.datamask.application.DataMask;
import ch.raph.datamask.application.MaskingEngine;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.serialization.Serializer;

/**
 * Masks a record on its way to the bytes, so the raw value is never written to a topic.
 *
 * {@snippet :
 * Serializer<Payment> serializer = new MaskingSerializer<>(new JsonSerializer<>(), dataMask);
 * Producer<String, Payment> producer = new KafkaProducer<>(configs, new StringSerializer(), serializer);
 * }
 *
 * <p>Or entirely from configuration, for a producer an application does not construct itself:
 *
 * <pre>{@code
 * value.serializer=ch.raph.datamask.kafka.MaskingSerializer
 * datamask.value.serializer=org.apache.kafka.common.serialization.StringSerializer
 * }</pre>
 *
 * <p>A topic is the worst place for PII to land. It is durable, it is replicated, it is retained for
 * as long as the topic says rather than as long as anyone intended, and its consumers are whoever
 * subscribed — a list nobody enumerated. Masking here is what makes those bytes safe before they are
 * anything but bytes.
 *
 * <h2>Keys</h2>
 *
 * This serializer masks whatever it is handed, so configuring it as {@code key.serializer} is itself
 * the decision to mask keys. Make it deliberately: the serialized key picks the partition and drives
 * log compaction, so only a deterministic strategy — {@code HASH} or {@code TOKENIZE} — keeps distinct
 * keys distinct. A {@code REDACT}ed key sends every record to one partition, and a compacted topic
 * then keeps only the last of them.
 *
 * <p>Which slot this is comes from {@code configure}, and Kafka does not call that on a serializer
 * instance handed to the producer's constructor. An instance masking keys therefore reports its
 * observer path as {@code kafka:value/<topic>} rather than {@code kafka:key/<topic>}. The masking is
 * the same either way; only the label on the report differs.
 *
 * <h2>Headers are not covered here</h2>
 *
 * Add {@link MaskingProducerInterceptor} for those. {@link Headers} has no in-place set — only add and
 * remove — so a serializer rewriting a header value would move it to the end of the list and reorder
 * what the caller wrote. The interceptor runs earlier, where the record can be rebuilt with its order
 * intact.
 *
 * <h2>A masking failure fails the send</h2>
 *
 * There is no fail-closed substitute for a payload: the placeholder is a string, and the delegate
 * expects a {@code T}. So a failure is thrown, the producer's {@code send} fails with it, and nothing
 * is published. That is the right way round for a topic — a failed send is retried or surfaced, while
 * a published record is permanent and has already been read.
 *
 * <p>Failures are rare by construction: the engine resolves a masker that throws according to
 * {@code MaskingPolicy#failureMode}, which by default redacts. What reaches here is a payload the
 * engine could not rebuild at all.
 */
public final class MaskingSerializer<T> implements Serializer<T> {

    // Headers are the interceptor's job, and which of the two serializer slots this was configured
    // into is what decides whether keys are masked, so neither of RecordMasker's settings is read
    // from the configuration here.
    private static final MaskerSource INSTALLED = MaskerSource.installed(false, Set.of());

    private final MaskerSource source;

    // Not final because Kafka builds this class by name through its no-argument constructor and hands
    // it the delegate in configure. Volatile because configure runs on the thread that constructs the
    // producer, and serialize on whichever thread calls send.
    private volatile Serializer<T> delegate;
    private volatile boolean isKey;

    /** For Kafka, which constructs this by name and configures it afterwards. */
    public MaskingSerializer() {
        this.source = INSTALLED;
    }

    /** Masks with whatever {@link DataMaskKafka#install} was given, resolved per record. */
    public MaskingSerializer(Serializer<T> delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.source = INSTALLED;
    }

    public MaskingSerializer(Serializer<T> delegate, DataMask dataMask) {
        this(delegate, Objects.requireNonNull(dataMask, "dataMask").engine());
    }

    public MaskingSerializer(Serializer<T> delegate, MaskingEngine engine) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.source = MaskerSource.of(new RecordMasker(engine));
    }

    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {
        this.isKey = isKey;

        if (delegate == null) {
            delegate = Settings.delegate(configs, isKey);
            if (delegate == null) {
                String key = isKey ? DataMaskKafka.KEY_DELEGATE_CONFIG : DataMaskKafka.VALUE_DELEGATE_CONFIG;
                // Refusing to start is the only safe answer. Serializing nothing is not an option, and
                // a default of "pass the object through" would silently publish it unmasked.
                throw new KafkaException(getClass().getName() + " needs the serializer to delegate to once it has"
                        + " masked; set " + key + ".");
            }
        }
        // Both routes end here, so a delegate is configured exactly once whether it came from a
        // constructor or from the configuration.
        delegate.configure(configs, isKey);
    }

    @Override
    public byte[] serialize(String topic, T data) {
        return delegate().serialize(topic, mask(topic, data));
    }

    @Override
    public byte[] serialize(String topic, Headers headers, T data) {
        return delegate().serialize(topic, headers, mask(topic, data));
    }

    private T mask(String topic, T data) {
        RecordMasker masker = source.get();
        return isKey ? masker.maskKey(data, topic) : masker.maskValue(data, topic);
    }

    private Serializer<T> delegate() {
        Serializer<T> serializer = delegate;
        if (serializer == null) {
            throw new IllegalStateException(getClass().getName()
                    + " was constructed without a delegate and never configured; pass one to the constructor or set "
                    + DataMaskKafka.VALUE_DELEGATE_CONFIG + ".");
        }
        return serializer;
    }

    /** The serializer this one masks for, for a caller that has to reach it. */
    public Serializer<T> delegateSerializer() {
        return delegate;
    }

    @Override
    public void close() {
        Serializer<T> serializer = delegate;
        if (serializer != null) {
            serializer.close();
        }
    }
}
