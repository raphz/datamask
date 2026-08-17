package ch.raph.datamask.kafka;

import ch.raph.datamask.api.MaskStrategy;
import ch.raph.datamask.api.PiiCategory;
import ch.raph.datamask.application.DataMask;
import ch.raph.datamask.application.MaskingEngine;
import ch.raph.datamask.domain.MaskingObserver;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;

/**
 * Turns a record into one that carries no PII.
 *
 * {@snippet :
 * RecordMasker masker = new RecordMasker(dataMask);
 * ProducerRecord<String, Payment> safe = masker.mask(record);
 * }
 *
 * <p>{@link MaskingProducerInterceptor} applies this to everything a producer sends,
 * {@link MaskingConsumerInterceptor} to everything a consumer polls, and {@link MaskingSerializer}
 * uses {@link #maskValue} on its own. It is public because the same job comes up elsewhere: a Kafka
 * Streams {@code Processor}, a Spring {@code ProducerListener}, a bridge that forwards records
 * between clusters.
 *
 * <h2>What is covered</h2>
 *
 * <ul>
 *   <li><b>The value</b> is masked as an object graph, so a {@code @PII} field of the payload is
 *       masked from its declaration rather than searched for in the bytes afterwards. A value that is
 *       a plain string is scanned instead, since there is no declaration to read.
 *   <li><b>Headers</b> are scanned as text. They are the half of a record nobody revisits: a
 *       correlation header added for one debugging session stays on every record afterwards, and it
 *       reaches consumers that were never told it exists.
 *   <li><b>The key</b> only when asked for. See the constructor.
 * </ul>
 *
 * <h2>The record is returned unchanged when it carried nothing</h2>
 *
 * That is the common case, and it costs no allocation: the engine and the text sanitiser both return
 * the <em>same instance</em> when nothing was masked, so this class compares references and forwards
 * the original record.
 *
 * <p>Thread-safe. A failure while masking a header is contained — reported to the
 * {@link MaskingObserver} and answered with the redaction placeholder — but a failure while masking
 * the key or the value is thrown, because there is no placeholder that is a valid value of the
 * payload's type. What the caller must not do with that exception is let the record through; see
 * {@link MaskingProducerInterceptor} for why that is easier to get wrong than it looks.
 */
public final class RecordMasker {

    private final MaskingEngine engine;
    private final MaskingObserver observer;

    /** Header text can only be masked by scanning it, so it follows the policy's own switch. */
    private final boolean scanText;

    private final boolean maskKeys;
    private final Set<String> redactedHeaders;

    public RecordMasker(DataMask dataMask) {
        this(Objects.requireNonNull(dataMask, "dataMask").engine());
    }

    public RecordMasker(MaskingEngine engine) {
        this(engine, false, Set.of());
    }

    /**
     * @param maskKeys whether {@link #mask(ProducerRecord)} masks the record key as well. Off by
     *     default because the key decides the partition and drives log compaction — see
     *     {@link DataMaskKafka#MASK_KEYS_CONFIG}. It governs the whole-record path only;
     *     {@link #maskKey} masks whatever it is handed, since asking for it is the point of calling it.
     * @param redactedHeaders header names whose value is replaced wholesale rather than scanned,
     *     compared ignoring case. For the internal identifiers no detector can recognise.
     */
    public RecordMasker(MaskingEngine engine, boolean maskKeys, Set<String> redactedHeaders) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.observer = engine.observer();
        this.scanText = engine.policy().scanUnannotatedText();
        this.maskKeys = maskKeys;
        this.redactedHeaders = lowerCased(redactedHeaders);
    }

    /** The same record when it carried no PII, a masked copy of it otherwise. */
    public <K, V> ProducerRecord<K, V> mask(ProducerRecord<K, V> record) {
        Objects.requireNonNull(record, "record");
        String topic = record.topic();

        V value = record.value();
        V maskedValue = maskValue(value, topic);

        K key = record.key();
        K maskedKey = maskKeys ? maskKey(key, topic) : key;

        Headers headers = record.headers();
        List<Header> maskedHeaders = maskHeaders(headers, topic);

        if (maskedValue == value && maskedKey == key && maskedHeaders == null) {
            return record;
        }

        // Rebuilt rather than mutated. ProducerRecord's headers are mutable, but Headers has no
        // in-place set — only add and remove — so rewriting a value through them would move that
        // header to the end of the list. Passing the masked headers to the constructor keeps the
        // order, and the duplicate keys, exactly as the caller wrote them.
        return new ProducerRecord<>(
                topic,
                record.partition(),
                record.timestamp(),
                maskedKey,
                maskedValue,
                maskedHeaders != null ? maskedHeaders : asList(headers));
    }

    /**
     * The same record when it carried no PII, a masked copy of it otherwise — for a record on its way
     * <em>in</em>.
     *
     * <p>The value is already deserialized by the time a consumer sees it, so this is the same
     * object-graph masking the producer side does, from what {@code @PII} declares rather than by
     * searching the bytes. What it is for is the record whose PII this process never wanted: a topic
     * with unmasked history, a consumer that only routes or counts, a framework that will put the
     * whole record into a log line or a dead-letter topic if the listener throws. See
     * {@link MaskingConsumerInterceptor} for when that is the right trade and when it is not.
     *
     * <p>Everything else about the record is carried across untouched — partition, offset, timestamp,
     * leader epoch, delivery count, and the serialized sizes, which describe the bytes that were
     * actually received and stay true of them.
     */
    public <K, V> ConsumerRecord<K, V> mask(ConsumerRecord<K, V> record) {
        Objects.requireNonNull(record, "record");
        String topic = record.topic();

        V value = record.value();
        V maskedValue = maskValue(value, topic);

        K key = record.key();
        K maskedKey = maskKeys ? maskKey(key, topic) : key;

        Headers headers = record.headers();
        List<Header> maskedHeaders = maskHeaders(headers, topic);

        if (maskedValue == value && maskedKey == key && maskedHeaders == null) {
            return record;
        }

        // RecordHeaders is Kafka's own and lives in an internals package, which this module otherwise
        // stays off — but ConsumerRecord's only public constructor takes a Headers, and this is the
        // only implementation Kafka ships. Unlike Header, which is a name and some bytes, there is no
        // two-line alternative that is not a reimplementation of a mutable collection.
        return new ConsumerRecord<>(
                topic,
                record.partition(),
                record.offset(),
                record.timestamp(),
                record.timestampType(),
                record.serializedKeySize(),
                record.serializedValueSize(),
                maskedKey,
                maskedValue,
                maskedHeaders != null ? new RecordHeaders(maskedHeaders) : headers,
                record.leaderEpoch(),
                record.deliveryCount());
    }

    /**
     * Masks a record value, returning the same instance when there was nothing to mask.
     *
     * <p>A declared payload is masked from its compiled plan, where {@code @PII}, {@code @NoMask} and
     * any policy override have already been resolved into one decision per member. A value that is
     * itself text has no declaration to read and is scanned instead.
     */
    public <V> V maskValue(V value, String topic) {
        return maskObject(value, path("value", topic));
    }

    /**
     * Masks a record key. Unlike {@link #mask(ProducerRecord)} this always masks, because a caller
     * that reached for it asked to.
     *
     * <p>Masking a key changes where the record lands and, on a compacted topic, which records
     * survive. Only a deterministic strategy — {@code HASH}, {@code TOKENIZE} — keeps distinct keys
     * distinct, and anything else collapses them onto one.
     */
    public <K> K maskKey(K key, String topic) {
        return maskObject(key, path("key", topic));
    }

    @SuppressWarnings("unchecked")
    private <T> T maskObject(T value, String path) {
        if (value == null) {
            return null;
        }
        // A CharSequence has no members to read a declaration from, so the detectors decide. Going
        // through maskText rather than the engine's own CharSequence branch is what gives the observer
        // a path that names the topic; the object branch below passes the same path into the engine
        // for the same reason.
        if (value instanceof CharSequence text) {
            if (!scanText) {
                return value;
            }
            String masked = engine.maskText(text, path);
            // By content rather than by reference, so that a CharSequence which is not a String — the
            // sanitiser has to build one of those to return it — still short-circuits and keeps its
            // own type on the way to the serializer.
            return masked.contentEquals(text) ? value : (T) masked;
        }
        // The engine's contract is a masked copy of the same type, which is what makes this cast
        // sound. If it ever were not, the serializer downstream rejects the value rather than
        // writing it: a type error fails the send, and a failed send discloses nothing.
        Object masked = engine.mask(value, path);
        if (masked == null) {
            // The engine degrades a structural failure — an unrebuildable or unreadable type — to
            // null, which is the right fail-closed answer for a log line. Here it is not: a null
            // record value is a tombstone, a different message, not less information. No
            // placeholder is a valid value of the payload's type, so the send fails instead.
            throw new IllegalStateException(
                    "a value of type " + value.getClass().getName() + " on " + path
                            + " could not be masked; failing the send rather than producing a tombstone");
        }
        return (T) masked;
    }

    /**
     * Masks header values, returning null when none of them changed.
     *
     * <p>Header names are left alone. They are identifiers a developer wrote, and rewriting one would
     * break every consumer that reads it by name.
     */
    private List<Header> maskHeaders(Headers headers, String topic) {
        if (headers == null) {
            return null;
        }
        Header[] present = headers.toArray();
        if (present.length == 0) {
            return null;
        }

        List<Header> masked = null;
        for (int i = 0; i < present.length; i++) {
            Header header = present[i];
            Header safe = maskHeader(header, topic);
            if (safe != header && masked == null) {
                masked = new ArrayList<>(List.of(present).subList(0, i));
            }
            if (masked != null) {
                masked.add(safe);
            }
        }
        return masked;
    }

    private Header maskHeader(Header header, String topic) {
        String path = path("header", topic) + "/" + header.key();
        byte[] value = header.value();
        if (value == null || value.length == 0) {
            return header;
        }

        try {
            if (redactedHeaders.contains(header.key().toLowerCase(Locale.ROOT))) {
                // Named by configuration precisely because no detector can recognise what it holds.
                observer.onMasked(path, PiiCategory.UNSPECIFIED, MaskStrategy.REDACT);
                return new MaskedHeader(header.key(), placeholder());
            }
            if (!scanText) {
                return header;
            }
            String text = decode(value);
            if (text == null) {
                // Not text, so no detector has anything to say about it. Left alone rather than
                // redacted: binary trace propagation and a framework's own binary metadata travel
                // this way, and destroying them would be reported as a bug rather than as
                // protection. A binary header carrying PII is out of this module's reach — mask the
                // payload it was serialised from instead.
                return header;
            }
            String safe = engine.maskText(text, path);
            return safe == text ? header : new MaskedHeader(header.key(), safe.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable failure) {
            // A header value is just bytes, so unlike a payload it has a fail-closed answer: the
            // placeholder. Includes the MaskingException that FailureMode.THROW raises — failing the
            // send over a header would be the larger outage, and the placeholder discloses nothing.
            observer.onFailure(path, failure);
            return new MaskedHeader(header.key(), placeholder());
        }
    }

    /** Null when the bytes are not UTF-8 text. Strict on purpose: a lenient decode invents characters. */
    private static String decode(byte[] value) {
        CharsetDecoder decoder = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(value)).toString();
        } catch (CharacterCodingException notText) {
            return null;
        }
    }

    private byte[] placeholder() {
        return engine.policy().redactionPlaceholder().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The observer this masker reports to, so an interceptor can report a failure of its own through
     * the same sink. Package-private: a caller that wants the observer for anything else already has
     * the engine it built this from.
     */
    MaskingObserver observer() {
        return observer;
    }

    /** Names the site the value came from, which is what makes an observer report actionable. */
    static String path(String part, String topic) {
        return "kafka:" + part + "/" + topic;
    }

    private static Set<String> lowerCased(Set<String> names) {
        Objects.requireNonNull(names, "redactedHeaders");
        return names.stream()
                .filter(name -> name != null && !name.isBlank())
                .map(name -> name.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    private static List<Header> asList(Headers headers) {
        return headers == null ? List.of() : List.of(headers.toArray());
    }

    /**
     * Kafka's own {@code RecordHeader} would do, but it lives in an {@code internals} package. A
     * {@code Header} is a name and some bytes, so implementing it here keeps this module off an
     * internal API for no loss.
     */
    private record MaskedHeader(String key, byte[] value) implements Header {}
}
