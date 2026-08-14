package ch.raph.datamask.log4j2;

import ch.raph.datamask.application.DataMask;
import ch.raph.datamask.application.MaskingEngine;
import java.util.Objects;
import org.apache.logging.log4j.status.StatusLogger;

/**
 * Resolves the masker a plugin uses, in this order: what was configured on the plugin, then what was
 * installed through {@link DataMaskLog4j2}, then a fallback of strict masking under an ephemeral key.
 *
 * <p>The fallback is loud and it is safe. Everything is masked; what an ephemeral key costs is that a
 * {@code HASH} pseudonym differs after a restart, which removes the reason to prefer it over
 * {@code REDACT}.
 *
 * <p>The installed instance is looked up per event rather than once, because a {@code DataMask} bean
 * cannot exist while a log4j2 configuration is being read. The result is cached against the instance it
 * came from, so the cost is a volatile read and a late install is still picked up.
 */
final class MaskerSource {

    private static final StatusLogger STATUS = StatusLogger.getLogger();

    private final LogEventMasker configured;
    private volatile Wiring wiring;

    private record Wiring(DataMask source, LogEventMasker masker) {}

    private MaskerSource(LogEventMasker configured) {
        this.configured = configured;
    }

    static MaskerSource forSecret(String secret) {
        return new MaskerSource(fromSecret(secret));
    }

    static MaskerSource of(DataMask dataMask) {
        return new MaskerSource(new LogEventMasker(Objects.requireNonNull(dataMask, "dataMask")));
    }

    static MaskerSource of(MaskingEngine engine) {
        return new MaskerSource(new LogEventMasker(Objects.requireNonNull(engine, "engine")));
    }

    LogEventMasker get() {
        LogEventMasker own = configured;
        if (own != null) {
            return own;
        }

        DataMask installed = DataMaskLog4j2.current();
        Wiring current = wiring;
        if (current != null && current.source() == installed) {
            return current.masker();
        }
        return rewire(installed);
    }

    /**
     * Keyed on the installed instance, which stays null while nothing is installed, so the fallback is
     * built once rather than per event — and replaced the moment something is installed.
     */
    private synchronized LogEventMasker rewire(DataMask installed) {
        Wiring current = wiring;
        if (current != null && current.source() == installed) {
            return current.masker();
        }

        DataMask resolved = installed != null ? installed : ephemeralFallback();
        Wiring next = new Wiring(installed, new LogEventMasker(resolved));
        wiring = next;
        return next.masker();
    }

    private static DataMask ephemeralFallback() {
        STATUS.error("datamask: no secret configured and no DataMask installed; masking with strict defaults and an"
                + " ephemeral key, so pseudonyms will not be comparable across restarts. Set a secret on the"
                + " plugin or call DataMaskLog4j2.install(...).");
        return DataMask.withDefaults();
    }

    private static LogEventMasker fromSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            return null;
        }
        try {
            return new LogEventMasker(DataMask.builder().secret(secret).build());
        } catch (RuntimeException rejected) {
            // A secret too short to pseudonymise with. Falling through to the installed instance or the
            // ephemeral fallback keeps every value masked; refusing to log at all would be the larger
            // outage. The message names no part of the secret.
            STATUS.error(
                    "datamask: the configured secret was rejected ({}); masking with an ephemeral key instead.",
                    rejected.getMessage());
            return null;
        }
    }
}
