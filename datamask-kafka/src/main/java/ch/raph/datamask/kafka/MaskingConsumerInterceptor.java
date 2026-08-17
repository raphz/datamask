package ch.raph.datamask.kafka;

import ch.raph.datamask.application.DataMask;
import ch.raph.datamask.application.MaskingEngine;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerInterceptor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Masks every record a consumer polls, headers included, before the application sees it.
 *
 * <pre>{@code
 * interceptor.classes=ch.raph.datamask.kafka.MaskingConsumerInterceptor
 * datamask.headers.redact=x-customer-ref,x-account
 * }</pre>
 *
 * <h2>This one is not the default, and should not be</h2>
 *
 * A consumer usually polls a topic because it needs what is in it. Masking on the way in destroys
 * exactly that, so this is opt-in per consumer, and the consumer it is for is the one whose job does
 * not involve the PII: an audit trail, a projection that counts and routes, a bridge that copies
 * records somewhere with a wider audience, a reader of a topic another team fills.
 *
 * <h2>What it is worth</h2>
 *
 * <ul>
 *   <li><b>A topic with history.</b> A producer-side masker only covers what is written after it is
 *       installed. Everything already on the topic — and everything a producer nobody controls still
 *       writes — is raw, and this is the only place a consuming application can do anything about it.
 *   <li><b>The framework's own error paths.</b> Spring Kafka's error handlers log the failed
 *       {@code ConsumerRecord}, and a {@code ConsumerRecord}'s {@code toString} contains the value. A
 *       listener that throws therefore prints the payload it choked on into the application log, at a
 *       level everything ships. Masking before the listener runs means the record they log is already
 *       masked. See this module's README for the full list, dead-letter topics included.
 * </ul>
 *
 * <h2>Keys are left alone unless asked for</h2>
 *
 * Set {@link DataMaskKafka#MASK_KEYS_CONFIG} to mask them. Partitioning is already decided by the
 * time a record is polled, so the reason here is a different one: application code deduplicates,
 * groups and correlates by key, and a masked key silently changes all three.
 *
 * <h2>Why a record that cannot be masked is dropped</h2>
 *
 * Kafka catches whatever {@code onConsume} throws, logs it, and carries on with <b>the records it had
 * before the interceptor ran</b> — the unmasked ones. Reporting a masking failure by throwing would
 * therefore deliver the very value it failed to mask, so a record that cannot be masked is left out of
 * what is returned, logged at ERROR naming the topic, partition and offset but never the value, and
 * reported to the {@code MaskingObserver} as {@code kafka:record/<topic>}.
 *
 * <p>That record is then skipped for good: nothing about a dropped record holds the offset back, so
 * the consumer commits past it like any other. It is data loss, and it is deliberate — the only
 * alternative on this path is handing the application the value this interceptor was installed to
 * remove. Failures are rare by construction: a masker that throws is resolved by
 * {@code MaskingPolicy#failureMode}, which by default redacts, so what reaches here is a payload the
 * engine could not rebuild at all. The ERROR log line is what turns one into a bug report.
 */
public final class MaskingConsumerInterceptor<K, V> implements ConsumerInterceptor<K, V> {

    private static final Logger LOG = LoggerFactory.getLogger(MaskingConsumerInterceptor.class);

    /**
     * For an interceptor constructed by hand and never configured. Nothing on this path is
     * configurable, so there is a right answer rather than an error: whatever was installed.
     */
    private static final MaskerSource DEFAULTS = MaskerSource.installed(false, Set.of());

    // Not final because Kafka builds this class by name and configures it afterwards. Volatile
    // because configure runs on the thread that constructs the consumer, and onConsume on whichever
    // one calls poll.
    private volatile MaskerSource source;

    /** For Kafka, which constructs this by name and configures it afterwards. */
    public MaskingConsumerInterceptor() {}

    public MaskingConsumerInterceptor(DataMask dataMask) {
        this(Objects.requireNonNull(dataMask, "dataMask").engine());
    }

    /** Keys off and no header redacted by name, which is what an unconfigured consumer would get. */
    public MaskingConsumerInterceptor(MaskingEngine engine) {
        this(new RecordMasker(engine));
    }

    public MaskingConsumerInterceptor(RecordMasker masker) {
        this.source = MaskerSource.of(masker);
    }

    @Override
    public void configure(Map<String, ?> configs) {
        if (source == null) {
            source = MaskerSource.installed(Settings.maskKeys(configs), Settings.redactedHeaders(configs));
        }
    }

    @Override
    public ConsumerRecords<K, V> onConsume(ConsumerRecords<K, V> records) {
        if (records == null || records.isEmpty()) {
            return records;
        }

        RecordMasker masker;
        try {
            masker = masker();
        } catch (Throwable unresolved) {
            // Returning the batch would deliver it unmasked, and throwing has Kafka do the same. An
            // empty batch is the only fail-closed answer left, and the poll simply looks empty.
            LOG.error("datamask: dropping {} polled records because no masker could be resolved", records.count(),
                    unresolved);
            return ConsumerRecords.empty();
        }

        Map<TopicPartition, List<ConsumerRecord<K, V>>> masked = new LinkedHashMap<>();
        boolean changed = false;
        for (TopicPartition partition : records.partitions()) {
            List<ConsumerRecord<K, V>> polled = records.records(partition);
            List<ConsumerRecord<K, V>> safe = new ArrayList<>(polled.size());
            for (ConsumerRecord<K, V> record : polled) {
                ConsumerRecord<K, V> maskedRecord = mask(masker, record);
                changed |= maskedRecord != record;
                if (maskedRecord != null) {
                    safe.add(maskedRecord);
                }
            }
            masked.put(partition, safe);
        }

        // The same batch when nothing carried PII, which is the common case on a topic that a masking
        // producer already wrote, and it costs no allocation beyond the walk.
        if (!changed) {
            return records;
        }
        // The next offsets are carried across as they were: they describe what the consumer read, and
        // a record this interceptor dropped was still read.
        return new ConsumerRecords<>(masked, records.nextOffsets());
    }

    /** The masked record, the same one when it carried nothing, or null when it has to be dropped. */
    private ConsumerRecord<K, V> mask(RecordMasker masker, ConsumerRecord<K, V> record) {
        try {
            return masker.mask(record);
        } catch (Throwable failure) {
            LOG.error(
                    "datamask: dropping the record at offset {} of {}-{} (value type {}) because it could not be"
                            + " masked. The application will not see it. No part of the value is reported here.",
                    record.offset(),
                    record.topic(),
                    record.partition(),
                    record.value() != null ? record.value().getClass().getName() : "null",
                    failure);
            report(masker, record.topic(), failure);
            return null;
        }
    }

    /**
     * The drop reaches the {@code MaskingObserver} as well as the log, so a metrics or audit sink
     * learns about a record the application never got. Guarded, because an observer that threw here
     * would leave {@code onConsume} throwing — and Kafka answers that by delivering the records it had
     * before the interceptor ran, the unmasked ones.
     */
    private void report(RecordMasker masker, String topic, Throwable failure) {
        try {
            masker.observer().onFailure(RecordMasker.path("record", topic), failure);
        } catch (Throwable observerFailed) {
            LOG.error("datamask: the observer threw while being told about a dropped record", observerFailed);
        }
    }

    private RecordMasker masker() {
        MaskerSource resolved = source;
        return (resolved != null ? resolved : DEFAULTS).get();
    }

    /** Nothing here has anything to say about offsets; they are the consumer's own bookkeeping. */
    @Override
    public void onCommit(Map<TopicPartition, OffsetAndMetadata> offsets) {}

    @Override
    public void close() {}
}
