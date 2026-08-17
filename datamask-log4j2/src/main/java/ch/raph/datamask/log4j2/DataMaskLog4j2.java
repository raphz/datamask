package ch.raph.datamask.log4j2;

import ch.raph.datamask.application.DataMask;
import ch.raph.datamask.application.InstalledDataMask;
import java.util.Optional;

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
 *
 * <p>The holder itself is {@link InstalledDataMask}, shared with every other integration that starts
 * before the application does — which is also where the "one instance per classloader that loaded the
 * holder" caveat is written down once for all of them.
 */
public final class DataMaskLog4j2 {

    private static final InstalledDataMask INSTALLED = InstalledDataMask.holder();

    private DataMaskLog4j2() {}

    /** Makes this instance the one every plugin without a secret of its own will use. */
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

    /** What the plugins resolve against; it is read on every event. */
    static InstalledDataMask holder() {
        return INSTALLED;
    }
}
