package ch.raph.datamask.kafka;

import ch.raph.datamask.application.DataMask;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves the masker a plugin uses: what was handed to its constructor, then what was installed
 * through {@link DataMaskKafka}, then a fallback of strict masking under an ephemeral key.
 *
 * <p>The fallback is loud and it is safe. Everything is masked; what an ephemeral key costs is that a
 * {@code HASH} pseudonym differs between instances and after a restart, which removes the reason to
 * prefer it over {@code REDACT} — a pseudonymised customer id stops correlating across the topic.
 *
 * <p>The installed instance is looked up per record rather than once, because a producer or consumer
 * may well be built before the {@code DataMask} it should use. The result is cached against the instance it came
 * from, so the cost is a volatile read and a late install is still picked up.
 */
final class MaskerSource {

    private static final Logger LOG = LoggerFactory.getLogger("ch.raph.datamask.kafka");

    private final RecordMasker configured;
    private final boolean maskKeys;
    private final Set<String> redactedHeaders;

    private volatile Wiring wiring;

    private record Wiring(DataMask source, RecordMasker masker) {}

    private MaskerSource(RecordMasker configured, boolean maskKeys, Set<String> redactedHeaders) {
        this.configured = configured;
        this.maskKeys = maskKeys;
        this.redactedHeaders = redactedHeaders;
    }

    static MaskerSource of(RecordMasker masker) {
        return new MaskerSource(Objects.requireNonNull(masker, "masker"), false, Set.of());
    }

    /** For a plugin Kafka built by name, which had nothing to be handed. */
    static MaskerSource installed(boolean maskKeys, Set<String> redactedHeaders) {
        return new MaskerSource(null, maskKeys, redactedHeaders);
    }

    RecordMasker get() {
        RecordMasker own = configured;
        if (own != null) {
            return own;
        }

        DataMask installed = DataMaskKafka.current();
        Wiring current = wiring;
        if (current != null && current.source() == installed) {
            return current.masker();
        }
        return rewire(installed);
    }

    /**
     * Keyed on the installed instance, which stays null while nothing is installed, so the fallback is
     * built once rather than per record — and replaced the moment something is installed.
     */
    private synchronized RecordMasker rewire(DataMask installed) {
        Wiring current = wiring;
        if (current != null && current.source() == installed) {
            return current.masker();
        }

        DataMask resolved = installed != null ? installed : ephemeralFallback();
        Wiring next = new Wiring(installed, new RecordMasker(resolved.engine(), maskKeys, redactedHeaders));
        wiring = next;
        return next.masker();
    }

    private static DataMask ephemeralFallback() {
        LOG.error("datamask: no DataMask installed for the Kafka client; masking with strict defaults and an"
                + " ephemeral key, so pseudonyms will not be comparable across restarts or between instances."
                + " Call DataMaskKafka.install(...) during startup.");
        return DataMask.withDefaults();
    }
}
