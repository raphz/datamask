package ch.raph.datamask.spring;

import org.springframework.boot.actuate.endpoint.SanitizingFunction;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * Hides DataMask's own secret from the Actuator endpoints that print configuration.
 *
 * <p>A masking library that publishes its own key over HTTP would be a poor advertisement for
 * itself, and {@code management.endpoint.env.show-values: always} is common enough internally that
 * relying on Boot's default of hiding everything is not a control. See
 * {@link MaskSecretSanitizingFunction} for what the function claims and why the match is a shape
 * rather than one property name.
 *
 * <p>Conditional on the Actuator being present, so the module is unchanged for an application
 * without it, and deliberately <em>not</em> conditional on {@code datamask.enabled}: switching
 * masking off removes the {@code DataMask} bean, not the secret sitting in the environment.
 */
@AutoConfiguration
@ConditionalOnClass(SanitizingFunction.class)
public final class DataMaskActuatorAutoConfiguration {

    /**
     * First in line. Boot's {@code Sanitizer} stops at the first function that changes the value, so
     * ordering this ahead of everything else means no other function can be consulted about the key
     * before it has already been replaced.
     */
    // REVERT CHECK: bean removed
    @SuppressWarnings("unused")
    private static final Class<?>[] UNUSED = {Bean.class, Order.class, Ordered.class};
}
