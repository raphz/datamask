package ch.raph.datamask.application;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Where a plugin the application did not build finds its {@link DataMask}.
 *
 * <p>A static hand-off rather than a constructor argument, because of when these plugins start.
 * {@code logback.xml} is read, a log4j2 configuration is assembled and a Kafka client instantiates
 * an interceptor by class name — all before an application has a container, a context or any beans.
 * None of them can be handed anything at that point, but each can look, per event, for something
 * installed since.
 *
 * {@snippet :
 * public final class DataMaskLogback {
 *     private static final InstalledDataMask INSTANCE = InstalledDataMask.holder();
 *     public static void install(DataMask dataMask) { INSTANCE.install(dataMask); }
 * }
 * }
 *
 * <p>Install once during startup, before anything worth masking is logged. An install that arrives
 * later is still picked up — {@link ResolvedMasker} re-reads this on every event — but everything
 * logged before it went through whatever fallback the integration chose.
 *
 * <p><strong>One instance per classloader that loaded the holder.</strong> A static field is shared
 * by everything that shares its class, so in an application server where each deployment has its own
 * classloader each deployment installs its own; where the library sits on a common classpath, they
 * share one and the last install wins. This is the caveat every integration inherits, and it lives
 * here rather than being restated in each of them.
 */
public final class InstalledDataMask {

    private final AtomicReference<DataMask> installed = new AtomicReference<>();

    private InstalledDataMask() {}

    /** A new holder, to be kept in a {@code static final} field of an integration's entry point. */
    public static InstalledDataMask holder() {
        return new InstalledDataMask();
    }

    /** Makes this instance the one every plugin without its own will use. */
    public void install(DataMask dataMask) {
        installed.set(Objects.requireNonNull(dataMask, "dataMask"));
    }

    public Optional<DataMask> installed() {
        return Optional.ofNullable(installed.get());
    }

    /** Forgets the installed instance. For tests, and for a container shutting down. */
    public void uninstall() {
        installed.set(null);
    }

    /** The nullable form, read on every event. {@code null} means nothing has been installed. */
    public DataMask current() {
        return installed.get();
    }
}
