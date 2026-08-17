package ch.raph.datamask.application;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * What one framework plugin masks with: whatever it was configured with, else whatever has been
 * installed through an {@link InstalledDataMask}, else the integration's own fallback.
 *
 * <p>Three integrations wrote this resolution by hand, identically, and the roadmap adds three more.
 * The part worth sharing is not the lookup but the caching rule: the derived masker is keyed on the
 * <em>identity of the installed instance</em>, which stays {@code null} for as long as nothing is
 * installed. So a fallback is built once rather than per event, and the moment something is
 * installed the key changes and the masker is rebuilt against it. Getting that wrong in six places
 * separately means either a {@code DataMask} constructed on the logging hot path or a late install
 * that never takes effect.
 *
 * @param <T> whatever the integration derives from a {@code DataMask} — a log event masker, a record
 *            masker, a serializer
 */
public final class ResolvedMasker<T> {

    private final T configured;
    private final InstalledDataMask holder;
    private final Function<DataMask, T> factory;
    private final Supplier<DataMask> fallback;

    private volatile Wiring<T> wiring;

    private record Wiring<T>(DataMask source, T masker) {}

    private ResolvedMasker(
            T configured, InstalledDataMask holder, Function<DataMask, T> factory, Supplier<DataMask> fallback) {
        this.configured = configured;
        this.holder = holder;
        this.factory = factory;
        this.fallback = fallback;
    }

    /** A plugin that was handed its own masker and never has to look anything up. */
    public static <T> ResolvedMasker<T> of(T masker) {
        return new ResolvedMasker<>(Objects.requireNonNull(masker, "masker"), null, null, null);
    }

    /**
     * A plugin the framework built by name, which had nothing to be handed.
     *
     * @param holder   where the integration's {@code install(...)} puts things
     * @param factory  derives the masker from whichever {@code DataMask} is resolved
     * @param fallback what to use while nothing is installed. It is called once per distinct
     *                 installed state, so this is where an integration logs the warning that its
     *                 pseudonyms will not be comparable — through its own logging framework's
     *                 internal status channel, never through the one being masked.
     */
    public static <T> ResolvedMasker<T> installed(
            InstalledDataMask holder, Function<DataMask, T> factory, Supplier<DataMask> fallback) {
        return new ResolvedMasker<>(
                null,
                Objects.requireNonNull(holder, "holder"),
                Objects.requireNonNull(factory, "factory"),
                Objects.requireNonNull(fallback, "fallback"));
    }

    /** The masker to use for this event. A volatile read in the common case. */
    public T get() {
        T own = configured;
        if (own != null) {
            return own;
        }

        DataMask current = holder.current();
        Wiring<T> cached = wiring;
        if (cached != null && cached.source() == current) {
            return cached.masker();
        }
        return rewire(current);
    }

    private synchronized T rewire(DataMask current) {
        Wiring<T> cached = wiring;
        if (cached != null && cached.source() == current) {
            return cached.masker();
        }

        Wiring<T> next = new Wiring<>(current, factory.apply(current != null ? current : fallback.get()));
        wiring = next;
        return next.masker();
    }
}
