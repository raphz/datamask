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
// The ordering after CompositeMeterRegistryAutoConfiguration is load-bearing, not cosmetic:
// @ConditionalOnBean evaluates against the bean DEFINITIONS present at processing time, and the
// MeterRegistry definitions come from the metrics export auto-configurations (Simple, Prometheus,
// ...) and the composite registry, all of which would otherwise sort AFTER this class
// (alphabetically, ch.raph.* precedes org.springframework.*). Without it the condition sees no
// registry, silently backs off, and the datamask.unannotated alert never exists. Every export
// auto-configuration orders before the composite one, so ordering after the composite covers them
// all — the same reason Boot's own JvmMetricsAutoConfiguration orders after it.
@AutoConfiguration(
        before = DataMaskAutoConfiguration.class,
        afterName = {
            "org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration",
            "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration"
        })
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnBean(MeterRegistry.class)
// Unlike the other integrations this class cannot gate on the DataMask bean (it runs before the
// core auto-configuration), so it repeats the master switch: no masking, no masking telemetry.
@ConditionalOnBooleanProperty(name = "datamask.enabled", matchIfMissing = true)
@ConditionalOnBooleanProperty(name = "datamask.metrics.enabled", matchIfMissing = true)
public final class DataMaskMetricsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    MicrometerMaskingObserver micrometerMaskingObserver(MeterRegistry registry) {
        return new MicrometerMaskingObserver(registry);
    }
}
