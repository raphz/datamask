package ch.raph.datamask.kafka;

import ch.raph.datamask.application.DataMask;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Where a serializer or interceptor named in a producer's configuration finds its {@link DataMask}.
 *
 * {@snippet :
 * DataMaskKafka.install(DataMask.builder().secret(System.getenv("DATAMASK_SECRET")).build());
 * }
 *
 * <p>A static hand-off because of how Kafka builds these plugins. Both {@code value.serializer} and
 * {@code interceptor.classes} are class <em>names</em>: the producer instantiates them through their
 * no-argument constructor and hands them nothing but a configuration map of strings, so there is no
 * argument to pass an engine in. An application that constructs its plugins itself — Spring's
 * {@code DefaultKafkaProducerFactory} takes serializer instances — should use the constructors
 * instead and leave this alone.
 *
 * <p>Install once during startup. The instance is looked up per record rather than at configure time,
 * so a producer created before the install still picks it up, from the next record on. Until then
 * masking runs under strict defaults and an ephemeral key: everything is masked, but a {@code HASH}
 * pseudonym will not match one produced after the install.
 *
 * <h2>Configuration keys</h2>
 *
 * The four keys below are read from the producer's configuration map. They share that map with every
 * other plugin, which is why each one is prefixed.
 */
public final class DataMaskKafka {

    /**
     * Whether the record key is masked as well. Off by default, and deliberately so: the key decides
     * the partition and drives log compaction, so masking it changes where records land and which of
     * them survive compaction. Switch it on only with a deterministic strategy — {@code HASH} or
     * {@code TOKENIZE} — which keeps distinct keys distinct. A {@code REDACT}ed key collapses every
     * record onto one key, and a compacted topic then keeps only the last of them.
     */
    public static final String MASK_KEYS_CONFIG = "datamask.mask.keys";

    /**
     * Header names whose value is replaced wholesale, comma-separated and compared ignoring case.
     *
     * <p>The detectors catch what they can recognise — an email, an IBAN, a card number — but a
     * header carrying an opaque internal identifier looks like any other short string. Naming it here
     * is how {@code x-customer-ref: 4711} gets masked, and it is worth going through the headers a
     * service sets once for exactly that reason.
     */
    public static final String REDACTED_HEADERS_CONFIG = "datamask.headers.redact";

    /**
     * The serializer {@link MaskingSerializer} delegates to once it has masked, when it is configured
     * as {@code key.serializer} by class name. Its own {@code configure} is called with the same map.
     */
    public static final String KEY_DELEGATE_CONFIG = "datamask.key.serializer";

    /** The same for {@code value.serializer}. */
    public static final String VALUE_DELEGATE_CONFIG = "datamask.value.serializer";

    private static final AtomicReference<DataMask> INSTALLED = new AtomicReference<>();

    private DataMaskKafka() {}

    /** Makes this instance the one every plugin without its own will use. */
    public static void install(DataMask dataMask) {
        INSTALLED.set(Objects.requireNonNull(dataMask, "dataMask"));
    }

    public static Optional<DataMask> installed() {
        return Optional.ofNullable(INSTALLED.get());
    }

    /** Forgets the installed instance. For tests, and for a container shutting down. */
    public static void uninstall() {
        INSTALLED.set(null);
    }

    /** The nullable form, for the plugins: this is read on every record. */
    static DataMask current() {
        return INSTALLED.get();
    }
}
