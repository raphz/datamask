package ch.raph.datamask.spring;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Publishes what the engine did to Micrometer, when the application already has a registry.
 *
 * <p>This is not decoration. A masking library that nobody measures degrades quietly: the mask
 * keeps working on the fields it knows about, and the field that was added last month leaks with no
 * outward sign. {@code datamask.unannotated} is what makes that visible — see
 * {@link MicrometerMaskingObserver} for the meters and for the tag that is deliberately absent.
 *
 * <p>Registered before the core auto-configuration so the observer is a bean by the time the
 * {@code DataMask} collects them. Ordering only decides when the definitions appear; either way the
 * engine picks up every {@code MaskingObserver} in the context, including this one alongside an
 * application's own audit sink.
 */
@AutoConfiguration(
        before = DataMaskAutoConfiguration.class,
        afterName = "org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration")
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnBean(MeterRegistry.class)
@ConditionalOnBooleanProperty(name = "datamask.metrics.enabled", matchIfMissing = true)
public final class DataMaskMetricsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    MicrometerMaskingObserver micrometerMaskingObserver(MeterRegistry registry) {
        return new MicrometerMaskingObserver(registry);
    }
}
