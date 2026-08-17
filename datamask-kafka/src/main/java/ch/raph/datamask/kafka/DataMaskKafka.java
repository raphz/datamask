package ch.raph.datamask.kafka;

import ch.raph.datamask.application.DataMask;
import ch.raph.datamask.application.InstalledDataMask;
import ch.raph.datamask.application.ResolvedMasker;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * <p>The hand-off itself is {@link InstalledDataMask}, shared with the logging integrations, and the
 * resolution per record is {@link ResolvedMasker}. Read the first for what a static field means where
 * several deployments share a classloader — the caveat this module inherits rather than restates.
 *
 * <h2>Configuration keys</h2>
 *
 * The four keys below are read from the configuration map of the client the plugin belongs to — a
 * producer's for the serializer and {@link MaskingProducerInterceptor}, a consumer's for
 * {@link MaskingConsumerInterceptor}. They share that map with every other plugin, which is why each
 * one is prefixed.
 */
public final class DataMaskKafka {

    /**
     * Whether the record key is masked as well. Off by default, and deliberately so: the key decides
     * the partition and drives log compaction, so masking it changes where records land and which of
     * them survive compaction. Switch it on only with a deterministic strategy — {@code HASH} or
     * {@code TOKENIZE} — which keeps distinct keys distinct. A {@code REDACT}ed key collapses every
     * record onto one key, and a compacted topic then keeps only the last of them.
     *
     * <p>On a consumer the partitioning is already decided, but application code deduplicates, groups
     * and correlates by key, so it is off there for the same reason with a different consequence.
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

    private static final Logger LOG = LoggerFactory.getLogger("ch.raph.datamask.kafka");

    private static final InstalledDataMask INSTALLED = InstalledDataMask.holder();

    private DataMaskKafka() {}

    /** Makes this instance the one every plugin without its own will use. */
    public static void install(DataMask dataMask) {
        INSTALLED.install(dataMask);
    }

    public static Optional<DataMask> installed() {
        return INSTALLED.installed();
    }

    /** Forgets the installed instance. For tests, and for a container shutting down. */
    public static void uninstall() {
        INSTALLED.uninstall();
    }

    /**
     * What a plugin Kafka built by name masks with, resolved per record: whatever was installed here,
     * else a fallback of strict masking under an ephemeral key.
     *
     * <p>The fallback is loud and it is safe. Everything is masked; what an ephemeral key costs is that
     * a {@code HASH} pseudonym differs between instances and after a restart, which removes the reason
     * to prefer it over {@code REDACT} — a pseudonymised customer id stops correlating across the topic.
     *
     * <p>The settings a plugin read out of its client's configuration are captured here rather than
     * held by the resolver, because they belong to the {@link RecordMasker} being built and not to the
     * lookup. The result is cached against the installed instance, so the fallback is built once rather
     * than per record and a late install is still picked up on the next one.
     */
    static ResolvedMasker<RecordMasker> resolving(boolean maskKeys, Set<String> redactedHeaders) {
        return ResolvedMasker.installed(
                INSTALLED,
                dataMask -> new RecordMasker(dataMask.engine(), maskKeys, redactedHeaders),
                DataMaskKafka::ephemeralFallback);
    }

    /**
     * Reported through SLF4J rather than through the core, so the warning stays where the integration
     * is: what a missing install costs is specific to Kafka's own hand-off, and naming it is what turns
     * an unconfigured producer into a fixed one. It names no value and no secret.
     */
    private static DataMask ephemeralFallback() {
        LOG.error("datamask: no DataMask installed for the Kafka client; masking with strict defaults and an"
                + " ephemeral key, so pseudonyms will not be comparable across restarts or between instances."
                + " Call DataMaskKafka.install(...) during startup.");
        return DataMask.withDefaults();
    }
}
