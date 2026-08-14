package ch.raph.datamask.spring;

import ch.raph.datamask.api.MaskStrategy;
import ch.raph.datamask.api.Sensitivity;
import ch.raph.datamask.domain.FailureMode;
import ch.raph.datamask.domain.MaskingPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Everything an application configures about masking, under the {@code datamask} prefix.
 *
 * <pre>{@code
 * datamask:
 *   secret: ${DATAMASK_SECRET}
 *   policy:
 *     preset: STRICT
 *     scan-unannotated-text: true
 * }</pre>
 *
 * <h2>The secret</h2>
 *
 * There is deliberately no default. {@link MaskStrategy#HASH} and {@link MaskStrategy#TOKENIZE} are
 * keyed, and a key shipped inside a library is a key everybody has — every pseudonym produced under
 * it would be reversible by anyone holding the artifact. So a context without {@link #secret()}
 * refuses to start, and the only way past that is to say {@link #ephemeralKey()} out loud, which is
 * for development and tests.
 *
 * <h2>The per-integration switches</h2>
 *
 * {@code datamask.jackson.enabled} and its siblings are read twice: as a condition on the
 * auto-configuration, which is what actually turns an integration off, and here, so the property is
 * documented in an IDE by {@code spring-boot-configuration-processor} rather than only discoverable
 * from this Javadoc.
 */
@ConfigurationProperties("datamask")
public record DataMaskProperties(
        @DefaultValue("true") boolean enabled,
        String secret,
        @DefaultValue("false") boolean ephemeralKey,
        @DefaultValue Policy policy,
        @DefaultValue Integration jackson,
        @DefaultValue Integration logback,
        @DefaultValue Integration log4j2,
        @DefaultValue Integration jdbc,
        @DefaultValue Integration kafka,
        @DefaultValue Integration metrics) {

    /** Whether a configured secret is present and usable. Says nothing about the value itself. */
    boolean hasSecret() {
        return secret != null && !secret.isBlank();
    }

    /** One integration's on/off switch. */
    public record Integration(@DefaultValue("true") boolean enabled) {}

    /**
     * The environment-dependent half of masking: how strictly this deployment treats data the
     * annotations have already described.
     *
     * <p>A {@link Preset} supplies every value, and each property below overrides one of them. Left
     * unset they stay {@code null} rather than taking a value of their own, so "not configured" and
     * "configured to the preset's value" cannot drift apart when a preset changes.
     */
    public record Policy(
            @DefaultValue("STRICT") Preset preset,
            Sensitivity threshold,
            FailureMode failureMode,
            String redactionPlaceholder,
            Integer maxDepth,
            Integer maxCollectionElements,
            Boolean scanUnannotatedText,
            Boolean maskMapKeys) {

        /** The two policies the library ships, as the base a deployment adjusts from. */
        public enum Preset {

            /** Mask everything annotated, scan free text, redact rather than throw. */
            STRICT,

            /** Hide only high-sensitivity data and leave free text alone, so local logs stay readable. */
            RELAXED
        }

        MaskingPolicy toMaskingPolicy() {
            MaskingPolicy base = preset == Preset.RELAXED ? MaskingPolicy.relaxed() : MaskingPolicy.strict();
            return new MaskingPolicy(
                    threshold != null ? threshold : base.threshold(),
                    failureMode != null ? failureMode : base.failureMode(),
                    redactionPlaceholder != null ? redactionPlaceholder : base.redactionPlaceholder(),
                    maxDepth != null ? maxDepth : base.maxDepth(),
                    maxCollectionElements != null ? maxCollectionElements : base.maxCollectionElements(),
                    scanUnannotatedText != null ? scanUnannotatedText : base.scanUnannotatedText(),
                    maskMapKeys != null ? maskMapKeys : base.maskMapKeys());
        }
    }
}
