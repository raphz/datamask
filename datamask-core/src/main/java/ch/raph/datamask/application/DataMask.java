package ch.raph.datamask.application;

import ch.raph.datamask.api.MaskStrategy;
import ch.raph.datamask.api.Masker;
import ch.raph.datamask.domain.MaskingObserver;
import ch.raph.datamask.domain.MaskingPolicy;
import ch.raph.datamask.domain.PiiDetector;
import ch.raph.datamask.domain.PiiFinding;
import ch.raph.datamask.domain.PolicyOverrides;
import ch.raph.datamask.domain.Pseudonymizer;
import ch.raph.datamask.domain.TokenVault;
import ch.raph.datamask.infrastructure.crypto.HmacPseudonymizer;
import ch.raph.datamask.infrastructure.crypto.MaskKey;
import ch.raph.datamask.infrastructure.detect.Detectors;
import ch.raph.datamask.infrastructure.generated.GeneratedMaskPlanCompiler;
import ch.raph.datamask.infrastructure.vault.InMemoryTokenVault;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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
    public <T> T mask(T value) {
        return (T) engine.mask(value);
    }

    /** Masks the PII inside a string and leaves the surrounding text readable. */
    public String maskText(CharSequence text) {
        return engine.maskText(text, "text");
    }

    /** Reports the PII in a string without changing it — for auditing what a payload contains. */
    public List<PiiFinding> scan(CharSequence text) {
        return engine.sanitizer().scan(text);
    }

    /** The keyed surrogate for a value, matching what {@link MaskStrategy#HASH} would produce. */
    public String pseudonymize(String value) {
        return pseudonymizer.pseudonymize(value);
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
        private MaskingPolicy policy = MaskingPolicy.strict();
        private TokenVault vault;
        private MaskingObserver observer = MaskingObserver.NOOP;
        private PolicyOverrides overrides = PolicyOverrides.none();
        private MaskPlanCompiler compiler;
        private List<PiiDetector> detectors;
        private final List<Runnable> maskerRegistrations = new ArrayList<>();
        private final MaskerRegistry maskers = MaskerRegistry.withDefaults();

        private Builder() {}

        /** The secret behind pseudonymisation. Must be at least sixteen bytes. */
        public Builder secret(String secret) {
            this.key = MaskKey.ofSecret(secret);
            return this;
        }

        public Builder key(MaskKey key) {
            this.key = Objects.requireNonNull(key, "key");
            return this;
        }

        public Builder policy(MaskingPolicy policy) {
            this.policy = Objects.requireNonNull(policy, "policy");
            return this;
        }

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

        public Builder detector(PiiDetector detector) {
            if (this.detectors == null) {
                this.detectors = new ArrayList<>(Detectors.defaults());
            } else {
                this.detectors = new ArrayList<>(this.detectors);
            }
            this.detectors.add(detector);
            return this;
        }

        /** Replaces a built-in strategy, for an institution-specific account or reference format. */
        public Builder masker(MaskStrategy strategy, Masker masker) {
            maskerRegistrations.add(() -> maskers.register(strategy, masker));
            return this;
        }

        /** Registers a custom masker instance, for implementations without a no-argument constructor. */
        public Builder masker(Masker masker) {
            maskerRegistrations.add(() -> maskers.register(masker));
            return this;
        }

        public DataMask build() {
            MaskKey resolvedKey = key != null ? key : MaskKey.ephemeral();
            TokenVault resolvedVault = vault != null ? vault : new InMemoryTokenVault();
            Pseudonymizer pseudonymizer = new HmacPseudonymizer(resolvedKey);
            List<PiiDetector> resolvedDetectors = detectors != null ? detectors : Detectors.defaults();

            maskerRegistrations.forEach(Runnable::run);

            MaskingPolicy effectivePolicy = policy;
            MaskContextFactory contexts = (descriptor, strategy, path, declaredType) -> new DefaultMaskContext(
                    descriptor, strategy, path, declaredType, effectivePolicy, pseudonymizer, resolvedVault);

            MaskPlanCompiler resolvedCompiler =
                    compiler != null ? compiler : GeneratedMaskPlanCompiler.orReflective(overrides);

            TextSanitizer sanitizer = new TextSanitizer(resolvedDetectors, maskers, contexts, observer);
            MaskingEngine engine =
                    new MaskingEngine(resolvedCompiler, maskers, effectivePolicy, sanitizer, contexts, observer);

            return new DataMask(engine, effectivePolicy, pseudonymizer, resolvedVault);
        }
    }
}
