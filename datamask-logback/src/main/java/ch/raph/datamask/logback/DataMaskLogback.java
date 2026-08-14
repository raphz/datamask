package ch.raph.datamask.logback;

import ch.raph.datamask.application.DataMask;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Where a {@link MaskingAppender} configured in XML finds its {@link DataMask}.
 *
 * {@snippet :
 * DataMaskLogback.install(DataMask.builder().secret(System.getenv("DATAMASK_SECRET")).build());
 * }
 *
 * <p>A static hand-off rather than a constructor argument because of when logging starts. {@code
 * logback.xml} is read before an application has a container, a context or any beans, so the appender
 * cannot be handed anything at that point — but it can look, per event, for something installed since.
 * Every appender without its own instance uses whatever is installed here, and one installed after the
 * first log line is picked up from the next one.
 *
 * <p>Install once, during startup, before anything worth masking is logged. An application without a
 * secret to configure can leave this alone: an appender with nothing to use falls back to strict
 * masking under an ephemeral key, which masks everything correctly but makes pseudonyms incomparable
 * across restarts.
 */
public final class DataMaskLogback {

    private static final AtomicReference<DataMask> INSTALLED = new AtomicReference<>();

    private DataMaskLogback() {}

    /** Makes this instance the one every {@link MaskingAppender} without its own will use. */
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

    /** The nullable form, for the appender: this is read on every event. */
    static DataMask current() {
        return INSTALLED.get();
    }
}
