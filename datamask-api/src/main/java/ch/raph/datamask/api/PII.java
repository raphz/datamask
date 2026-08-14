package ch.raph.datamask.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that a value carries personally identifiable information and must never leave the
 * process in clear text.
 *
 * <p>Placed on a record component, field, getter or parameter it marks that single value:
 * {@snippet :
 * public record Customer(@PII Email email,
 *                        @PII(strategy = MaskStrategy.HASH) String iban,
 *                        String country) { }
 * }
 *
 * <p>Placed on a type it marks <em>every</em> use of that type, which is the more robust option
 * for a domain that already models PII as value objects:
 * {@snippet :
 * @PII(category = PiiCategory.EMAIL)
 * public record Email(String value) { }
 * }
 */
@Target({
    ElementType.FIELD,
    ElementType.RECORD_COMPONENT,
    ElementType.METHOD,
    ElementType.PARAMETER,
    ElementType.TYPE,
    ElementType.ANNOTATION_TYPE
})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
public @interface PII {

    /**
     * How the value is masked. {@link MaskStrategy#AUTO} — the default — resolves the strategy
     * from {@link #category()}, then from a {@code @PII} annotation on the declared type, then
     * from what the value's content is detected to be, and finally falls back to full redaction.
     */
    MaskStrategy strategy() default MaskStrategy.AUTO;

    /** What kind of personal data this is. Drives {@code AUTO} and compliance reporting. */
    PiiCategory category() default PiiCategory.UNSPECIFIED;

    /** How damaging disclosure would be. Policies mask everything at or above their threshold. */
    Sensitivity sensitivity() default Sensitivity.HIGH;

    /**
     * A custom masker, which takes precedence over {@link #strategy()}. Implementations must have
     * a public no-argument constructor, or be registered explicitly on the engine.
     */
    Class<? extends Masker> masker() default Masker.class;

    /**
     * How many trailing characters {@link MaskStrategy#PARTIAL} leaves visible. {@code -1} means
     * "use the category's default", which is what keeps PAN masking inside PCI-DSS limits.
     */
    int keep() default -1;

    /** The character substituted for every hidden character. */
    char padding() default '*';

    /** A fixed replacement, used verbatim instead of computing one. */
    String replacement() default "";

    /**
     * Why this field is collected. Not used by the engine; it surfaces in the compliance report so
     * a GDPR Article 30 record of processing can be generated from the code itself.
     */
    String purpose() default "";
}
