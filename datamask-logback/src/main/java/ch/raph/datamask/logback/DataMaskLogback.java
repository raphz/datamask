package ch.raph.datamask.logback;

import ch.raph.datamask.application.DataMask;
import ch.raph.datamask.application.InstalledDataMask;
import java.util.Optional;

/**
 * Where a {@link MaskingAppender} configured in XML finds its {@link DataMask}.
 *
 * {@snippet :
 * DataMaskLogback.install(DataMask.builder().secret(System.getenv("DATAMASK_SECRET")).build());
 * }
 *
 * <p>A static hand-off rather than a constructor argument because of when logging starts: {@code
 * logback.xml} is read before an application has a container, a context or any beans. {@link
 * InstalledDataMask} is the shared support type behind it, and carries why the hand-off is static and
 * what a per-classloader static field means for a deployment; this class only names it for logback.
 *
 * <p>Install once, during startup, before anything worth masking is logged. An appender re-reads this
 * on every event, so an instance installed after the first log line is picked up from the next one.
 * An application without a secret to configure can leave this alone: an appender with nothing to use
 * falls back to strict masking under an ephemeral key, which masks everything correctly but makes
 * pseudonyms incomparable across restarts.
 */
public final class DataMaskLogback {

    private static final InstalledDataMask INSTANCE = InstalledDataMask.holder();

    private DataMaskLogback() {}

    /** Makes this instance the one every {@link MaskingAppender} without its own will use. */
    public static void install(DataMask dataMask) {
        INSTANCE.install(dataMask);
    }

    public static Optional<DataMask> installed() {
        return INSTANCE.installed();
    }

    /** Forgets the installed instance. For tests, and for a container shutting down. */
    public static void uninstall() {
        INSTANCE.uninstall();
    }

    /** The holder itself, for the appender's {@code ResolvedMasker}: this is read on every event. */
    static InstalledDataMask holder() {
        return INSTANCE;
    }
}
