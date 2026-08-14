package ch.raph.datamask.spring;

import static org.assertj.core.api.Assertions.assertThat;

import ch.raph.datamask.api.MaskStrategy;
import ch.raph.datamask.api.PII;
import ch.raph.datamask.api.PiiCategory;
import ch.raph.datamask.application.DataMask;
import ch.raph.datamask.domain.MaskingObserver;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@DisplayName("Masking is measurable, because a mask nobody measures decays without anything looking wrong")
class MicrometerMaskingObserverTest {

    private static final String SECRET = "test-secret-0123456789";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(
                    AutoConfigurations.of(DataMaskMetricsAutoConfiguration.class, DataMaskAutoConfiguration.class))
            .withPropertyValues("datamask.secret=" + SECRET);

    @Nested
    @DisplayName("The wiring")
    class Wiring {

        @Test
        @DisplayName("registers the observer when the application already has a meter registry, and the "
                + "engine picks it up like any other observer bean")
        void registersWhenARegistryExists() {
            runner.withBean(MeterRegistry.class, SimpleMeterRegistry::new).run(context -> {
                assertThat(context).hasSingleBean(MicrometerMaskingObserver.class);

                context.getBean(DataMask.class).mask(new Customer("bruno@example.com"));

                MeterRegistry registry = context.getBean(MeterRegistry.class);
                assertThat(registry.get("datamask.masked")
                                .tag("category", "EMAIL")
                                .counter()
                                .count())
                        .isEqualTo(1.0);
            });
        }

        @Test
        @DisplayName("stays absent when there is no registry to publish to")
        void absentWithoutARegistry() {
            runner.run(context -> assertThat(context).doesNotHaveBean(MicrometerMaskingObserver.class));
        }

        @Test
        @DisplayName("stays absent when Micrometer is not on the classpath at all")
        void absentWithoutMicrometer() {
            runner.withClassLoader(new FilteredClassLoader(MeterRegistry.class))
                    .run(context -> assertThat(context).hasNotFailed().hasSingleBean(DataMask.class));
        }

        @Test
        @DisplayName("can be switched off without losing the masking it measures")
        void disabled() {
            runner.withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                    .withPropertyValues("datamask.metrics.enabled=false")
                    .run(context -> {
                        assertThat(context).doesNotHaveBean(MicrometerMaskingObserver.class);
                        assertThat(context).hasSingleBean(DataMask.class);
                    });
        }
    }

    @Nested
    @DisplayName("The meters")
    class Meters {

        private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        private final MaskingObserver observer = new MicrometerMaskingObserver(registry);

        @Test
        @DisplayName("counts a declared value by category and strategy, which is how the shape of what the "
                + "model actually carries becomes visible")
        void countsMaskedValues() {
            observer.onMasked("Customer#email", PiiCategory.EMAIL, MaskStrategy.EMAIL);
            observer.onMasked("Customer#email", PiiCategory.EMAIL, MaskStrategy.EMAIL);
            observer.onMasked("Card#pan", PiiCategory.PAN, MaskStrategy.PAN);

            assertThat(registry.get("datamask.masked")
                            .tag("category", "EMAIL")
                            .tag("strategy", "EMAIL")
                            .counter()
                            .count())
                    .isEqualTo(2.0);
            assertThat(registry.get("datamask.masked")
                            .tag("category", "PAN")
                            .counter()
                            .count())
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("counts PII nobody declared separately, because that is the alert: a field is leaking "
                + "and the annotation for it does not exist yet")
        void countsUnannotatedPii() {
            observer.onUnannotatedPii("logback:message", PiiCategory.IBAN, "iban");

            assertThat(registry.get("datamask.unannotated")
                            .tag("category", "IBAN")
                            .tag("detector", "iban")
                            .counter()
                            .count())
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("counts failures and depth limits, the two ways masking stops short of what was asked")
        void countsFailuresAndDepthLimits() {
            observer.onFailure("Customer#email", new IllegalStateException("boom"));
            observer.onDepthLimitExceeded("Portfolio#accounts[3]");

            assertThat(registry.get("datamask.failures").counter().count()).isEqualTo(1.0);
            assertThat(registry.get("datamask.depth.limit.exceeded").counter().count())
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("never puts the path in a tag: it is unbounded, and in a map it is sometimes the data " + "itself")
        void thePathIsNotATag() {
            observer.onMasked("Customer#email", PiiCategory.EMAIL, MaskStrategy.EMAIL);

            assertThat(registry.get("datamask.masked").counter().getId().getTags())
                    .extracting("key")
                    .containsExactlyInAnyOrder("category", "strategy");
        }

        @Test
        @DisplayName("still counts when the engine reports no category or strategy, rather than throwing on "
                + "the masking hot path")
        void toleratesMissingTags() {
            observer.onMasked("somewhere", null, null);
            observer.onUnannotatedPii("somewhere", null, "  ");

            assertThat(registry.get("datamask.masked")
                            .tag("category", "unknown")
                            .tag("strategy", "unknown")
                            .counter()
                            .count())
                    .isEqualTo(1.0);
            assertThat(registry.get("datamask.unannotated")
                            .tag("detector", "unknown")
                            .counter()
                            .count())
                    .isEqualTo(1.0);
        }
    }

    record Customer(@PII(category = PiiCategory.EMAIL) String email) {}
}
