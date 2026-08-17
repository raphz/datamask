package ch.raph.datamask.application;

import ch.raph.datamask.api.MaskStrategy;
import ch.raph.datamask.api.Masker;
import ch.raph.datamask.domain.MaskingException;
import ch.raph.datamask.infrastructure.masker.DateGeneralizeMasker;
import ch.raph.datamask.infrastructure.masker.EmailMasker;
import ch.raph.datamask.infrastructure.masker.FormatPreservingMasker;
import ch.raph.datamask.infrastructure.masker.HashMasker;
import ch.raph.datamask.infrastructure.masker.IbanMasker;
import ch.raph.datamask.infrastructure.masker.IpAddressMasker;
import ch.raph.datamask.infrastructure.masker.NameMasker;
import ch.raph.datamask.infrastructure.masker.NullifyMasker;
import ch.raph.datamask.infrastructure.masker.PanMasker;
import ch.raph.datamask.infrastructure.masker.PartialMasker;
import ch.raph.datamask.infrastructure.masker.PhoneMasker;
import ch.raph.datamask.infrastructure.masker.RedactMasker;
import ch.raph.datamask.infrastructure.masker.TokenizeMasker;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Resolves a strategy, or a named custom implementation, to the masker that carries it out. */
public final class MaskerRegistry {

    private final Map<MaskStrategy, Masker> byStrategy;
    private final Map<Class<? extends Masker>, Masker> byType = new ConcurrentHashMap<>();
    private final Masker redact = new RedactMasker();

    private MaskerRegistry(Map<MaskStrategy, Masker> byStrategy) {
        this.byStrategy = byStrategy;
    }

    public static MaskerRegistry withDefaults() {
        Map<MaskStrategy, Masker> maskers = new EnumMap<>(MaskStrategy.class);
        maskers.put(MaskStrategy.REDACT, new RedactMasker());
        maskers.put(MaskStrategy.PARTIAL, new PartialMasker());
        maskers.put(MaskStrategy.HASH, new HashMasker());
        maskers.put(MaskStrategy.TOKENIZE, new TokenizeMasker());
        maskers.put(MaskStrategy.NULLIFY, new NullifyMasker());
        maskers.put(MaskStrategy.EMAIL, new EmailMasker());
        maskers.put(MaskStrategy.NAME, new NameMasker());
        maskers.put(MaskStrategy.IBAN, new IbanMasker());
        maskers.put(MaskStrategy.PAN, new PanMasker());
        maskers.put(MaskStrategy.PHONE, new PhoneMasker());
        maskers.put(MaskStrategy.IP, new IpAddressMasker());
        maskers.put(MaskStrategy.DATE_GENERALIZE, new DateGeneralizeMasker());
        maskers.put(MaskStrategy.PRESERVE_FORMAT, new FormatPreservingMasker());
        return new MaskerRegistry(maskers);
    }

    /** Overrides a built-in, so an institution can impose its own IBAN or account format. */
    public MaskerRegistry register(MaskStrategy strategy, Masker masker) {
        byStrategy.put(strategy, masker);
        return this;
    }

    /** Pre-registers a custom masker instance, for implementations without a no-argument constructor. */
    public MaskerRegistry register(Masker masker) {
        byType.put(masker.getClass(), masker);
        return this;
    }

    /**
     * The masker for a strategy. {@code AUTO} and {@code SCAN} never reach here — the engine
     * resolves the first and handles the second itself — so an unmapped strategy falls back to
     * redaction rather than to disclosure.
     */
    public Masker forStrategy(MaskStrategy strategy) {
        return byStrategy.getOrDefault(strategy, redact);
    }

    public Masker forType(Class<? extends Masker> type) {
        return byType.computeIfAbsent(type, MaskerRegistry::instantiate);
    }

    public Masker redacting() {
        return redact;
    }

    private static Masker instantiate(Class<? extends Masker> type) {
        try {
            return type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw MaskingException.atPath(
                    type.getName(),
                    "custom masker needs a public no-argument constructor, or must be registered "
                            + "explicitly on the DataMask builder",
                    e);
        }
    }
}
