package ch.raph.datamask.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Exempts a member from masking that a type-level {@link PII} annotation or an external policy
 * would otherwise apply.
 *
 * <p>The justification is mandatory and deliberately so: an unexplained exemption on a PII-bearing
 * type is exactly the change that should not pass review unnoticed. It is reported alongside the
 * field in the compliance report.
 */
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface NoMask {

    /** Why this value is safe to disclose. Required. */
    String justification();
}
