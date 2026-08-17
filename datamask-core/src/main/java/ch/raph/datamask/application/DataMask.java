package ch.raph.datamask.application;

import ch.raph.datamask.api.MaskStrategy;
import ch.raph.datamask.api.Masker;
import ch.raph.datamask.api.PiiCategory;
import ch.raph.datamask.domain.MaskingObserver;
import ch.raph.datamask.domain.MaskingPolicy;
import ch.raph.datamask.domain.PiiDescriptor;
import ch.raph.datamask.domain.PiiDetector;
import ch.raph.datamask.domain.PiiFinding;
import ch.raph.datamask.domain.PolicyOverrides;
import ch.raph.datamask.domain.Pseudonymizer;
import ch.raph.datamask.domain.TokenVault;
import ch.raph.datamask.infrastructure.crypto.HmacPseudonymizer;
import ch.raph.datamask.infrastructure.crypto.MaskKey;
import ch.raph.datamask.infrastructure.detect.Detectors;
import ch.raph.datamask.infrastructure.generated.GeneratedMaskPlanCompiler;
import ch.raph.datamask.infrastructure.vault.RejectingTokenVault;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * The entry point.
 *
 * {@snippet :
 * DataMask dataMask = DataMask.builder().secret(System.getenv("DATAMASK_SECRET")).build();
 * Customer safe = dataMask.mask(customer);
 * }
 *
 * <p>Instances are immutable and thread-safe, and are meant to be created once per application.
 */
public final class DataMask {

    private final MaskingEngine engine;
    private final MaskingPolicy policy;
    private final Pseudonymizer pseudonymizer;
    private final TokenVault vault;

    private DataMask(MaskingEngine engine, MaskingPolicy policy, Pseudonymizer pseudonymizer, TokenVault vault) {
        this.engine = engine;
        this.policy = policy;
        this.pseudonymizer = pseudonymizer;
        this.vault = vault;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * A strict instance with a random per-JVM key. Convenient for tests; not for production, where
     * an ephemeral key makes pseudonyms incomparable across instances and restarts.
     */
    public static DataMask withDefaults() {
        return builder().build();
    }

    /**
     * Returns a masked copy of the value. The original is left untouched.
     *
     * <p>The result has the same type as the input for records, for beans with a usable
     * constructor, and for collections and maps of those.
     */
    @SuppressWarnings("unchecked")
    public <T> @Nullable T mask(@Nullable T value) {
        return (T) engine.mask(value);
    }

    /**
     * Masks one value against a category the caller already knows, without needing an annotated
     * type to hang it on — a header, a query parameter, a value pulled out of a map.
     *
     * <p>The category decides the strategy, exactly as {@code @PII(category = …)} on a field would.
     */
    public @Nullable Object maskValue(@Nullable Object value, PiiCategory category) {
        return maskValue(value, category, "value");
    }

    /**
     * The same, with the path that reaches {@link MaskingObserver}. Worth passing: the observer
     * signal is only actionable if it says which value it was about.
     */
    public @Nullable Object maskValue(@Nullable Object value, PiiCategory category, String path) {
        Objects.requireNonNull(category, "category");
        Class<?> declaredType = value == null ? Object.class : value.getClass();
        return engine.maskDeclared(value, PiiDescriptor.of(category), declaredType, path);
    }

    /** Masks the PII inside a string and leaves the surrounding text readable. Null in, null out. */
    public @Nullable String maskText(@Nullable CharSequence text) {
        return maskText(text, "text");
    }

    /** The same, with the path reported to {@link MaskingObserver#onUnannotatedPii}. */
    public @Nullable String maskText(@Nullable CharSequence text, String path) {
        return engine.maskText(text, path);
    }

    /**
     * Reports the PII in a string without changing it — for auditing what a payload contains.
     * Null or empty text has no findings.
     */
    public List<PiiFinding> scan(@Nullable CharSequence text) {
        return engine.sanitizer().scan(text);
    }

    /** The keyed surrogate for a value, matching what {@link MaskStrategy#HASH} would produce. */
    public String pseudonymize(String value) {
        return pseudonymizer.pseudonymize(value);
    }

    /**
     * Whether a pseudonym stands for this value — including one issued under a key this deployment
     * has since rotated away from, which is what makes a rotation something other than a break in
     * every join. Recomputes rather than reverses; a surrogate stays one-way.
     */
    public boolean pseudonymMatches(String value, String pseudonym) {
        return pseudonymizer instanceof HmacPseudonymizer hmac && hmac.matches(value, pseudonym);
    }

    /** Resolves a token issued by {@link MaskStrategy#TOKENIZE}. */
    public Optional<String> detokenize(String token) {
        return vault == null ? Optional.empty() : vault.detokenize(token);
    }

    public MaskingEngine engine() {
        return engine;
    }

    public MaskingPolicy policy() {
        return policy;
    }

    public TokenVault vault() {
        return vault;
    }

    /** Assembles an engine from its parts, applying the defaults an application rarely needs to change. */
    public static final class Builder {

        private MaskKey key;
        private final List<MaskKey> previousKeys = new ArrayList<>();
        private MaskingPolicy policy = MaskingPolicy.strict();
        private TokenVault vault;
        private MaskingObserver observer = MaskingObserver.NOOP;
        private PolicyOverrides overrides = PolicyOverrides.none();
        private MaskPlanCompiler compiler;
        private List<PiiDetector> detectors;
        // Recorded rather than applied, so each build() can replay them onto a registry of its own.
        // A registry shared across builds would let a masker registered after one build() change
        // how an already-created DataMask masks — action at a distance, in a security library.
        private final List<Consumer<MaskerRegistry>> maskerRegistrations = new ArrayList<>();

        private Builder() {}

        /** The secret behind pseudonymisation. Must be at least sixteen bytes. */
        public Builder secret(String secret) {
            this.key = MaskKey.ofSecret(secret);
            return this;
        }

        /**
         * The same, from a {@code char[]} — worth preferring, because a {@code String} secret cannot
         * be wiped and stays readable in any heap dump taken before it is collected.
         */
        public Builder secret(char[] secret) {
            this.key = MaskKey.ofSecret(secret);
            return this;
        }

        public Builder key(MaskKey key) {
            this.key = Objects.requireNonNull(key, "key");
            return this;
        }

        /**
         * A key this deployment has rotated away from. Repeatable.
         *
         * <p>Pseudonyms carry the id of the key that issued them, so one issued before a rotation
         * can still be confirmed against the value it stands for — see
         * {@link #pseudonymMatches(String, String)}. Keep a previous key for as long as data
         * pseudonymised under it is still being joined, and no longer.
         */
        public Builder previousKey(MaskKey previous) {
            previousKeys.add(Objects.requireNonNull(previous, "previous"));
            return this;
        }

        /** A secret this deployment has rotated away from, derived the same way {@link #secret(String)} is. */
        public Builder previousSecret(String previous) {
            return previousKey(MaskKey.ofSecret(previous));
        }

        public Builder policy(MaskingPolicy policy) {
            this.policy = Objects.requireNonNull(policy, "policy");
            return this;
        }

        /**
         * The vault behind {@link MaskStrategy#TOKENIZE}. Without one, tokenisation refuses and the
         * value is redacted instead — see {@link RejectingTokenVault} for why that is the default.
         */
        public Builder vault(TokenVault vault) {
            this.vault = vault;
            return this;
        }

        public Builder observer(MaskingObserver observer) {
            this.observer = Objects.requireNonNull(observer, "observer");
            return this;
        }

        /** Masking for types that cannot be annotated, such as generated DTOs. */
        public Builder overrides(PolicyOverrides overrides) {
            this.overrides = Objects.requireNonNull(overrides, "overrides");
            return this;
        }

        /**
         * Replaces how plans are derived.
         *
         * <p>The default already prefers plans generated by {@code datamask-build-processor} and
         * reflects over whatever has none, so this is for a compiler an application wrote itself —
         * or for a test that wants one of the two in isolation.
         */
        public Builder compiler(MaskPlanCompiler compiler) {
            this.compiler = Objects.requireNonNull(compiler, "compiler");
            return this;
        }

        /** Replaces the default detector set. Pass an empty list to disable content scanning. */
        public Builder detectors(List<PiiDetector> detectors) {
            this.detectors = List.copyOf(detectors);
            return this;
        }

        /**
         * Adds a detector after the built-in ones, so it only classifies text no built-in claimed.
         *
         * <p>Order is priority: overlapping findings are resolved earliest-start, then longest, then
         * by position in this list. Use {@link #detectorFirst} when the point of the detector is
         * that it knows better than a built-in.
         */
        public Builder detector(PiiDetector detector) {
            mutableDetectors().add(Objects.requireNonNull(detector, "detector"));
            return this;
        }

        /**
         * Adds a detector ahead of the built-in ones, so it wins any tie against them.
         *
         * <p>This is what an institution-specific format needs. A contract reference that happens to
         * pass Luhn would otherwise be reported and masked as a payment card for as long as the
         * built-in detector is consulted first, and appending could never change that.
         */
        public Builder detectorFirst(PiiDetector detector) {
            mutableDetectors().addFirst(Objects.requireNonNull(detector, "detector"));
            return this;
        }

        private List<PiiDetector> mutableDetectors() {
            detectors = new ArrayList<>(detectors != null ? detectors : Detectors.defaults());
            return detectors;
        }

        /** Replaces a built-in strategy, for an institution-specific account or reference format. */
        public Builder masker(MaskStrategy strategy, Masker masker) {
            maskerRegistrations.add(registry -> registry.register(strategy, masker));
            return this;
        }

        /** Registers a custom masker instance, for implementations without a no-argument constructor. */
        public Builder masker(Masker masker) {
            maskerRegistrations.add(registry -> registry.register(masker));
            return this;
        }

        public DataMask build() {
            MaskKey resolvedKey = key != null ? key : MaskKey.ephemeral();
            TokenVault resolvedVault = vault != null ? vault : RejectingTokenVault.INSTANCE;
            Pseudonymizer pseudonymizer = new HmacPseudonymizer(resolvedKey, previousKeys);
            List<PiiDetector> resolvedDetectors = detectors != null ? detectors : Detectors.defaults();

            MaskerRegistry maskers = MaskerRegistry.withDefaults();
            maskerRegistrations.forEach(registration -> registration.accept(maskers));

            MaskingPolicy effectivePolicy = policy;
            MaskContextFactory contexts = (descriptor, strategy, path, declaredType) -> new DefaultMaskContext(
                    descriptor, strategy, path, declaredType, effectivePolicy, pseudonymizer, resolvedVault);

            MaskPlanCompiler resolvedCompiler =
                    compiler != null ? compiler : GeneratedMaskPlanCompiler.orReflective(overrides);

            TextSanitizer sanitizer =
                    new TextSanitizer(resolvedDetectors, maskers, contexts, observer, effectivePolicy);
            MaskingEngine engine =
                    new MaskingEngine(resolvedCompiler, maskers, effectivePolicy, sanitizer, contexts, observer);

            return new DataMask(engine, effectivePolicy, pseudonymizer, resolvedVault);
        }
    }
}
