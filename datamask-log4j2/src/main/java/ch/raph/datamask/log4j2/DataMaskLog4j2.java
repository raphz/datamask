package ch.raph.datamask.log4j2;

import ch.raph.datamask.application.DataMask;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Where the log4j2 plugins find their {@link DataMask}.
 *
 * {@snippet :
 * DataMaskLog4j2.install(DataMask.builder().secret(System.getenv("DATAMASK_SECRET")).build());
 * }
 *
 * <p>A static hand-off rather than a constructor argument because of when logging starts. A log4j2
 * configuration is read before an application has a container, a context or any beans, so a plugin
 * cannot be handed anything at that point — but it can look, per event, for something installed since.
 * Every plugin without a {@code secret} of its own uses whatever is installed here, and one installed
 * after the first log line is picked up from the next one.
 *
 * <p>Install once, during startup, before anything worth masking is logged. An application with no
 * secret to configure can leave this alone: a plugin with nothing to use falls back to strict masking
 * under an ephemeral key, which masks everything correctly but makes pseudonyms incomparable across
 * restarts.
 */
public final class DataMaskLog4j2 {

    private static final AtomicReference<DataMask> INSTALLED = new AtomicReference<>();

    private DataMaskLog4j2() {}

    /** Makes this instance the one every plugin without a secret of its own will use. */
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

    /** The nullable form, for the plugins: this is read on every event. */
    static DataMask current() {
        return INSTALLED.get();
    }
}
