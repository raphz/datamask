package ch.raph.datamask.log4j2;

import ch.raph.datamask.application.DataMask;
import ch.raph.datamask.application.MaskingEngine;
import ch.raph.datamask.application.ResolvedMasker;
import java.util.Objects;
import org.apache.logging.log4j.status.StatusLogger;

/**
 * Builds the {@link ResolvedMasker} a plugin masks through, in this order: what was configured on the
 * plugin, then what was installed through {@link DataMaskLog4j2}, then a fallback of strict masking
 * under an ephemeral key.
 *
 * <p>The lookup and, more importantly, its caching rule live in {@link ResolvedMasker}, shared with
 * every other integration that starts before the application does. What is log4j2's own is here:
 * reading a {@code secret} off a plugin attribute, and reporting through log4j2's
 * {@link StatusLogger} — never through the logger being masked, which is the value's own way out.
 */
final class LogEventMaskers {

    private static final StatusLogger STATUS = StatusLogger.getLogger();

    private LogEventMaskers() {}

    /** For a plugin log4j2 built from a configuration: its own secret when usable, else the installed instance. */
    static ResolvedMasker<LogEventMasker> forSecret(String secret) {
        LogEventMasker configured = fromSecret(secret);
        return configured != null ? ResolvedMasker.of(configured) : fromInstalled();
    }

    /** For an application that builds its own — a Spring auto-configuration, or a test. */
    static ResolvedMasker<LogEventMasker> of(DataMask dataMask) {
        return ResolvedMasker.of(new LogEventMasker(Objects.requireNonNull(dataMask, "dataMask")));
    }

    static ResolvedMasker<LogEventMasker> of(MaskingEngine engine) {
        return ResolvedMasker.of(new LogEventMasker(Objects.requireNonNull(engine, "engine")));
    }

    private static ResolvedMasker<LogEventMasker> fromInstalled() {
        return ResolvedMasker.installed(
                DataMaskLog4j2.holder(), LogEventMasker::new, LogEventMaskers::ephemeralFallback);
    }

    /**
     * The fallback is loud and it is safe. Everything is masked; what an ephemeral key costs is that a
     * {@code HASH} pseudonym differs after a restart, which removes the reason to prefer it over
     * {@code REDACT}.
     *
     * <p>Called once per distinct installed state rather than per event, which is what makes an error
     * on the logging path affordable.
     */
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
