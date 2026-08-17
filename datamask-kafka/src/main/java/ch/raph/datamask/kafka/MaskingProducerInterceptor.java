package ch.raph.datamask.kafka;

import ch.raph.datamask.application.DataMask;
import ch.raph.datamask.application.MaskingEngine;
import ch.raph.datamask.application.ResolvedMasker;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Masks every record a producer sends, headers included, without touching the serializers.
 *
 * <pre>{@code
 * interceptor.classes=ch.raph.datamask.kafka.MaskingProducerInterceptor
 * datamask.headers.redact=x-customer-ref,x-account
 * }</pre>
 *
 * <p>This is the route in when the serializer cannot be swapped — a schema-registry serde configured
 * by a platform team, a framework that sets its own, a producer built somewhere the application code
 * does not reach. It runs in {@code KafkaProducer.send}, before the key and value are serialized and
 * before a partition is assigned, which is why it can mask the payload as an object graph rather than
 * as bytes.
 *
 * <h2>Headers are the reason to prefer it</h2>
 *
 * A {@link MaskingSerializer} cannot rewrite a header without reordering the list, because
 * {@code Headers} has no in-place set. Here the record is rebuilt, so the headers keep their order and
 * their duplicate keys. That matters more than it sounds: headers are the half of a record nobody
 * revisits. A correlation header added for one debugging session stays on every record afterwards, it
 * is copied by every framework that bridges topics, and it reaches consumers that were never told it
 * exists.
 *
 * <h2>Keys are left alone unless asked for</h2>
 *
 * Set {@link DataMaskKafka#MASK_KEYS_CONFIG} to mask them, and read what it says first: a masked key
 * changes the partition a record lands on and which records a compacted topic keeps.
 *
 * <h2>Why a failure does not throw</h2>
 *
 * Kafka catches whatever {@code onSend} throws, logs it, and carries on with <b>the record it had
 * before the interceptor ran</b> — the unmasked one. An interceptor that reports a masking failure by
 * throwing therefore publishes exactly the value it failed to mask, which is the one outcome this
 * library exists to prevent.
 *
 * <p>So a failure is logged here, at ERROR, naming the topic and the path but never the value, and the
 * record is dropped by returning null. The producer then fails that {@code send} from inside itself —
 * a {@code NullPointerException}, which reads poorly on its own and is why the log line beside it
 * says what actually happened. A failed send is retried or surfaced; a published record is permanent
 * and has already been read.
 *
 * <p>The drop is also reported to the {@code MaskingObserver} as {@code kafka:record/<topic>}, so it
 * reaches metrics and an audit sink rather than only a log file.
 *
 * <p>Failures are rare by construction: a masker that throws is resolved according to
 * {@code MaskingPolicy#failureMode}, which by default redacts. What reaches here is a payload the
 * engine could not rebuild at all — or {@code FailureMode.THROW}, configured by someone who wanted the
 * bug surfaced.
 */
public final class MaskingProducerInterceptor<K, V> implements ProducerInterceptor<K, V> {

    private static final Logger LOG = LoggerFactory.getLogger(MaskingProducerInterceptor.class);

    /**
     * For an interceptor constructed by hand and never configured. Nothing on this path is
     * configurable, so there is a right answer rather than an error: whatever was installed.
     */
    private static final ResolvedMasker<RecordMasker> DEFAULTS = DataMaskKafka.resolving(false, Set.of());

    // Not final because Kafka builds this class by name and configures it afterwards. Volatile because
    // configure runs on the thread that constructs the producer, and onSend on whichever calls send.
    private volatile ResolvedMasker<RecordMasker> source;

    /** For Kafka, which constructs this by name and configures it afterwards. */
    public MaskingProducerInterceptor() {}

    public MaskingProducerInterceptor(DataMask dataMask) {
        this(Objects.requireNonNull(dataMask, "dataMask").engine());
    }

    /** Keys off and no header redacted by name, which is what an unconfigured producer would get. */
    public MaskingProducerInterceptor(MaskingEngine engine) {
        this(new RecordMasker(engine));
    }

    public MaskingProducerInterceptor(RecordMasker masker) {
        this.source = ResolvedMasker.of(masker);
    }

    @Override
    public void configure(Map<String, ?> configs) {
        if (source == null) {
            source = DataMaskKafka.resolving(Settings.maskKeys(configs), Settings.redactedHeaders(configs));
        }
    }

    @Override
    public ProducerRecord<K, V> onSend(ProducerRecord<K, V> record) {
        if (record == null) {
            return null;
        }
        try {
            return masker().mask(record);
        } catch (Throwable failure) {
            LOG.error(
                    "datamask: dropping a record for topic {} (value type {}) because it could not be masked. The send"
                            + " will fail and nothing was published. No part of the value is reported here.",
                    record.topic(),
                    record.value() != null ? record.value().getClass().getName() : "null",
                    failure);
            report(record.topic(), failure);
            return null;
        }
    }

    /**
     * The drop goes to the {@link ch.raph.datamask.domain.MaskingObserver} as well as to the log, so a
     * metrics or audit sink learns that a record was dropped rather than only a log file that nobody
     * has an alert on. The path names the whole record, {@code kafka:record/<topic>}, because that is
     * what was lost — a header failure reports itself per header and never reaches here.
     *
     * <p>Guarded, and this guard is the point rather than defensiveness: an observer that threw would
     * leave {@code onSend} throwing, and Kafka answers a throwing {@code onSend} by sending the record
     * it had before the interceptor ran — the unmasked one. The same is true of resolving the masker,
     * which is why that happens in here too.
     */
    private void report(String topic, Throwable failure) {
        try {
            masker().observer().onFailure(RecordMasker.path("record", topic), failure);
        } catch (Throwable observerFailed) {
            LOG.error("datamask: the observer threw while being told about a dropped record", observerFailed);
        }
    }

    private RecordMasker masker() {
        ResolvedMasker<RecordMasker> resolved = source;
        return (resolved != null ? resolved : DEFAULTS).get();
    }

    @Override
    public void close() {}
}
