package ch.raph.datamask.spring;

import ch.raph.datamask.api.Masker;
import ch.raph.datamask.application.DataMask;
import ch.raph.datamask.application.MaskingEngine;
import ch.raph.datamask.domain.FailureMode;
import ch.raph.datamask.domain.MaskingObserver;
import ch.raph.datamask.domain.MaskingPolicy;
import ch.raph.datamask.domain.PiiDetector;
import ch.raph.datamask.domain.PolicyOverrides;
import ch.raph.datamask.domain.TokenVault;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Builds the one {@link DataMask} every other auto-configuration in this module wires up.
 *
 * <p>The properties describe the parts an operator changes between environments — the secret and
 * the policy. Everything else is a bean: a {@link TokenVault}, a {@link PolicyOverrides} for types
 * that cannot be annotated, extra {@link PiiDetector}s, custom {@link Masker}s, and any number of
 * {@link MaskingObserver}s. A {@link DataMaskBuilderCustomizer} gets the last word.
 *
 * <p>Declaring a {@code DataMask} bean of your own switches all of that off and the rest of the
 * module wires up whatever you built instead, which is what {@code @ConditionalOnMissingBean} is
 * for here.
 *
 * <h2>Why this can refuse to start</h2>
 *
 * {@code HASH} and {@code TOKENIZE} are keyed. Without {@code datamask.secret} the only options are
 * a key of the library's own — which would be public, and would make every pseudonym in production
 * reversible — or a random one per JVM, which is safe but makes pseudonyms incomparable across
 * restarts and instances, quietly removing the reason to prefer hashing over redaction. Neither is
 * a defensible default, so the context fails instead. See {@link MissingMaskSecretException}.
 *
 * <p>A secret that is present but shorter than the minimum fails the same way, through
 * {@link ShortMaskSecretException}: padding it out would be the same mistake as a shipped default,
 * because the values {@code HASH} covers come from an input space small enough to enumerate.
 */
@AutoConfiguration
@ConditionalOnClass(DataMask.class)
@ConditionalOnBooleanProperty(name = "datamask.enabled", matchIfMissing = true)
@EnableConfigurationProperties(DataMaskProperties.class)
public final class DataMaskAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DataMaskAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    DataMask dataMask(
            DataMaskProperties properties,
            ObjectProvider<TokenVault> vault,
            ObjectProvider<PolicyOverrides> overrides,
            ObjectProvider<PiiDetector> detectors,
            ObjectProvider<Masker> maskers,
            ObjectProvider<MaskingObserver> observers,
            ObjectProvider<DataMaskBuilderCustomizer> customizers) {

        MaskingPolicy policy = properties.policy().toMaskingPolicy();
        warnAboutPassThrough(policy);

        DataMask.Builder builder = DataMask.builder().policy(policy);
        applyKey(builder, properties);

        vault.ifAvailable(builder::vault);
        overrides.ifAvailable(builder::overrides);
        detectors.orderedStream().forEach(builder::detector);
        maskers.orderedStream().forEach(builder::masker);

        observer(observers).ifPresent(builder::observer);
        customizers.orderedStream().forEach(customizer -> customizer.customize(builder));

        return builder.build();
    }

    /**
     * The engine is what an integration written by hand takes, so it is worth injecting directly
     * rather than reaching through the facade at every call site.
     */
    @Bean
    @ConditionalOnMissingBean
    MaskingEngine maskingEngine(DataMask dataMask) {
        return dataMask.engine();
    }

    /**
     * Resolves the key, or refuses to.
     *
     * <p>A secret and {@code ephemeral-key=true} together is a profile that inherited both. The
     * secret wins, because between two configured answers the safe one is the stronger one, and the
     * disagreement is worth a line in the log.
     */
    private static void applyKey(DataMask.Builder builder, DataMaskProperties properties) {
        if (properties.hasSecret()) {
            if (properties.ephemeralKey()) {
                log.warn("Both datamask.secret and datamask.ephemeral-key are set; using the configured "
                        + "secret and ignoring datamask.ephemeral-key.");
            }
            try {
                builder.secret(properties.secret());
            } catch (IllegalArgumentException e) {
                // The minimum length is the only thing MaskKey.ofSecret rejects a non-null secret
                // for, so this is that rule. Translated at the boundary rather than left to reach an
                // operator as a stack trace out of the crypto adapter, which reads like a library
                // bug rather than a value that needs to be longer. Nothing derived from the secret
                // crosses into the new exception; see ShortMaskSecretFailureAnalyzer.
                throw new ShortMaskSecretException(e);
            }
            return;
        }
        if (!properties.ephemeralKey()) {
            throw new MissingMaskSecretException();
        }
        // Left unset on the builder, which is what produces an ephemeral key.
        log.warn("DataMask is masking under an ephemeral key because datamask.ephemeral-key is set. "
                + "Everything is still masked, but a HASH pseudonym will differ after a restart and "
                + "between instances. Configure datamask.secret outside development.");
    }

    private static void warnAboutPassThrough(MaskingPolicy policy) {
        if (policy.failureMode() == FailureMode.PASS_THROUGH) {
            log.warn("datamask.policy.failure-mode is PASS_THROUGH: a structural masking failure will "
                    + "emit the original value. This exists for local debugging and must not reach an "
                    + "environment holding real data.");
        }
    }

    /**
     * Empty when nothing was declared, so the builder keeps {@code MaskingObserver.NOOP} rather than
     * paying for a composite around nothing.
     */
    private static Optional<MaskingObserver> observer(ObjectProvider<MaskingObserver> observers) {
        List<MaskingObserver> declared = observers.orderedStream().toList();
        return switch (declared.size()) {
            case 0 -> Optional.empty();
            case 1 -> Optional.of(declared.getFirst());
            default -> Optional.of(new CompositeMaskingObserver(declared));
        };
    }
}
