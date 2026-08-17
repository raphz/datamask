package ch.raph.datamask.spring;

import ch.raph.datamask.api.MaskStrategy;
import ch.raph.datamask.api.Sensitivity;
import ch.raph.datamask.domain.FailureMode;
import ch.raph.datamask.domain.MaskingPolicy;
import java.util.List;
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
 *
 * <p>The five of them share the {@link Integration} record, so what each one actually stops masking
 * cannot be said on its component. It is said per property in
 * {@code META-INF/additional-spring-configuration-metadata.json} instead, which the processor merges
 * over the generated descriptions — the file is part of this documentation, not a leftover.
 *
 * <h2>Why the {@code @param} text below is plain prose</h2>
 *
 * Every one of them becomes the description an IDE shows while somebody types in
 * {@code application.yml}, and the processor copies the text through verbatim — an inline tag or an
 * HTML element reaches the tooltip as those literal characters. So the {@code @param} text carries
 * no markup, which is a constraint on this Javadoc alone and not a style for the rest of the
 * codebase.
 *
 * @param enabled Whether DataMask builds anything at all. Off, there is no DataMask bean, so every
 *     integration backs off with it and nothing in the application is masked — including the
 *     Micrometer observer that would otherwise report PII nobody annotated. To keep masking but drop
 *     one consumer of it, turn that integration off rather than this.
 * @param secret The HMAC secret behind the HASH and TOKENIZE strategies, at least 16 bytes of UTF-8.
 *     There is deliberately no default and no fallback: a key shipped inside a library is a key
 *     everybody has, and the values HASH covers — IBANs, phone numbers, AVS numbers — come from an
 *     input space small enough to enumerate against a known key, so every pseudonym would be
 *     reversible. A context without it refuses to start. Supply it from a secret manager or the
 *     environment; it is kept out of the /env and /configprops endpoints. Every pseudonym names the
 *     key that made it, so rotating this makes new pseudonyms stop matching the ones already
 *     written.
 * @param ephemeralKey Starts without a secret by masking under a key generated fresh in each JVM.
 *     For tests and local development only. Everything is still masked, but a HASH pseudonym then
 *     differs after every restart and between every instance, so the same customer stops looking
 *     like the same customer across two log lines — which removes the entire reason to prefer HASH
 *     over REDACT, and with it any support or audit workflow built on correlating pseudonyms. It is
 *     safe rather than leaky; it is simply worth nothing in an environment holding real data, and
 *     there is no built-in default key to reach for instead. Ignored, with a warning, when a secret
 *     is also configured.
 * @param policy How strictly this deployment treats the data the annotations already describe.
 * @param jackson The Jackson integration, which masks every document an ObjectMapper writes.
 * @param logback The Logback integration, which hands this application's key to a MaskingAppender
 *     declared in logback.xml.
 * @param log4j2 The Log4j2 integration, which does the same for the rewrite policy and pattern
 *     converter declared in log4j2.xml.
 * @param jdbc The JDBC integration, which keeps a database's own error messages from quoting the row
 *     they are about.
 * @param kafka The Kafka integration, which hands this application's key to serializers and
 *     interceptors the client builds by class name.
 * @param metrics The Micrometer integration, which publishes what the engine did — including the
 *     counter that fires when a detector finds PII nobody annotated.
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
        @DefaultValue Jdbc jdbc,
        @DefaultValue Integration kafka,
        @DefaultValue Integration metrics) {

    /** Whether a configured secret is present and usable. Says nothing about the value itself. */
    boolean hasSecret() {
        return secret != null && !secret.isBlank();
    }

    /**
     * One integration's on/off switch.
     *
     * @param enabled Whether DataMask wires itself into this integration. Off, the integration is
     *     left exactly as it would be without DataMask on the classpath — for a serializer that
     *     means unmasked output, for a logging framework it means masking under an ephemeral key
     *     instead of the configured one. Which of the two applies differs per integration and is
     *     described on each property; the master switch is datamask.enabled.
     */
    public record Integration(@DefaultValue("true") boolean enabled) {}

    /**
     * The JDBC integration's switch, plus the one thing wrapping every {@code DataSource} needs an
     * escape hatch for.
     *
     * <p>The wrapper is a {@code MaskingDataSource} and not a {@code HikariDataSource}, so an
     * injection point declared as the pool's own type stops resolving once the bean is wrapped.
     * Naming that bean here leaves it alone. It is the last resort rather than the first: an
     * unwrapped pool is one whose unique-constraint violations still quote the row they collided
     * with, so prefer injecting {@code DataSource} and unwrapping, which keeps the masking.
     *
     * @param enabled Whether DataMask wraps the application's DataSource beans at all. Off, the
     *     driver's own diagnostics go out untouched: a unique-constraint violation keeps quoting the
     *     row it collided with, and a statement's bind parameters keep reaching the exception and
     *     whatever logs it — so a duplicate-key error on an email column prints the email.
     * @param excludedBeans Names of DataSource beans to leave unwrapped, for the one problem
     *     wrapping causes: an injection point declared as the pool's own type, HikariDataSource for
     *     instance, stops resolving once the bean is a MaskingDataSource. A pool named here goes
     *     back to quoting row values and bind parameters in its errors, so prefer injecting
     *     DataSource and unwrapping, which keeps the masking. Each exclusion is logged at startup.
     */
    public record Jdbc(@DefaultValue("true") boolean enabled, List<String> excludedBeans) {

        public Jdbc {
            excludedBeans = excludedBeans == null ? List.of() : List.copyOf(excludedBeans);
        }
    }

    /**
     * The environment-dependent half of masking: how strictly this deployment treats data the
     * annotations have already described.
     *
     * <p>A {@link Preset} supplies every value, and each property below overrides one of them. Left
     * unset they stay {@code null} rather than taking a value of their own, so "not configured" and
     * "configured to the preset's value" cannot drift apart when a preset changes.
     *
     * @param preset The baseline the properties below adjust from. STRICT masks everything annotated
     *     LOW and above, scans free text and masks map keys. RELAXED masks only HIGH and CRITICAL
     *     and leaves free text and map keys alone, so a developer's log stays readable — which makes
     *     it a development setting: under it a MEDIUM field, a postal address or a date of birth, is
     *     written in full.
     * @param threshold The lowest sensitivity still masked, overriding the preset's: LOW under
     *     STRICT, HIGH under RELAXED. Raising it unmasks every annotation below the new value in one
     *     step and says nothing about which fields those are, because sensitivity is declared per
     *     annotation on the domain model. HIGH lets every LOW and MEDIUM value through untouched.
     * @param failureMode What the engine does when masking a value fails, overriding the preset's,
     *     which is REDACT — the only defensible choice in production, because a masking bug has to
     *     degrade to less information rather than more. THROW breaks the test that found the bug
     *     instead of quietly redacting a field nobody notices. PASS_THROUGH emits the original value
     *     when the engine cannot rebuild a structure, which is PII in the clear by construction; it
     *     exists so a developer can debug locally, it never applies when a masker itself fails, and
     *     it is warned about at startup.
     * @param redactionPlaceholder What a redacted value is replaced with, overriding the preset's,
     *     which is four asterisks. It changes what a reader sees, never how much of the original
     *     survives, so the only thing at stake is legibility: an empty string makes a redacted field
     *     indistinguishable from an absent one, and a placeholder shaped like real data makes a
     *     masked log harder to trust at a glance.
     * @param maxDepth How deep the engine walks an object graph before it stops, overriding the
     *     preset's 32. Anything below the limit becomes null rather than being emitted unmasked, so
     *     lowering this costs fields in the output and discloses nothing, while raising it costs
     *     traversal time on every deep graph. Each stop reaches MaskingObserver.onDepthLimitExceeded.
     * @param maxCollectionElements How many elements of a collection or map are masked before the
     *     tail is dropped, overriding the preset's 1000. The bound is what keeps one runaway
     *     collection from turning a log statement into an outage; the dropped elements are absent
     *     from the masked copy rather than passed through, so this too trades output for time and
     *     never for disclosure.
     * @param scanUnannotatedText Whether strings nobody annotated are scanned for PII — card
     *     numbers, IBANs, AVS numbers, emails, the identifiers among them verified by their check
     *     digits — overriding the preset's setting, on under STRICT and off under RELAXED. Off, an
     *     IBAN pasted into an exception message, a free-text comment or a log argument is written
     *     verbatim, and MaskingObserver.onUnannotatedPii stops firing with it. That signal is the
     *     earliest warning that a newly added field is leaking, so turning this off also turns off
     *     the way you would find out.
     * @param maskMapKeys Whether map keys are masked as well as values, overriding the preset's
     *     setting, on under STRICT and off under RELAXED. A map keyed by email address or customer
     *     reference is a common shape and a key discloses as much as a value, so off writes those
     *     keys in full. What leaving it on costs is lookup semantics: the masked copy is no longer
     *     keyed by anything the caller can look up.
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
