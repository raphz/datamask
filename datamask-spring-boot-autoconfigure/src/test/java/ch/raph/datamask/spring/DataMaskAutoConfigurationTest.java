package ch.raph.datamask.spring;

import static org.assertj.core.api.Assertions.assertThat;

import ch.raph.datamask.api.MaskContext;
import ch.raph.datamask.api.MaskStrategy;
import ch.raph.datamask.api.Masker;
import ch.raph.datamask.api.PII;
import ch.raph.datamask.api.PiiCategory;
import ch.raph.datamask.api.Sensitivity;
import ch.raph.datamask.application.DataMask;
import ch.raph.datamask.application.MaskingEngine;
import ch.raph.datamask.domain.FailureMode;
import ch.raph.datamask.domain.MaskingObserver;
import ch.raph.datamask.domain.MaskingPolicy;
import ch.raph.datamask.domain.PiiDescriptor;
import ch.raph.datamask.domain.PiiDetector;
import ch.raph.datamask.domain.PiiFinding;
import ch.raph.datamask.domain.PolicyOverrides;
import ch.raph.datamask.domain.TokenVault;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@DisplayName("The auto-configuration builds one DataMask from properties and beans, and refuses to build "
        + "one it cannot key")
class DataMaskAutoConfigurationTest {

    /** Twenty bytes, so MaskKey accepts it. Nothing in this file is a real secret. */
    private static final String SECRET = "test-secret-0123456789";

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(DataMaskAutoConfiguration.class));

    @Nested
    @DisplayName("The key")
    class Key {

        @Test
        @DisplayName("refuses to start without a secret, because the alternative is a key everyone holding "
                + "the artifact also has")
        void failsWithoutASecret() {
            runner.run(context -> assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .isInstanceOf(MissingMaskSecretException.class));
        }

        @Test
        @DisplayName("starts once a secret is configured")
        void startsWithASecret() {
            runner.withPropertyValues("datamask.secret=" + SECRET)
                    .run(context -> assertThat(context).hasSingleBean(DataMask.class));
        }

        @Test
        @DisplayName("accepts an ephemeral key only when it was asked for out loud")
        void startsWithAnExplicitEphemeralKey() {
            runner.withPropertyValues("datamask.ephemeral-key=true").run(context -> {
                assertThat(context).hasSingleBean(DataMask.class);
                // Ephemeral or not, everything is still masked.
                Customer masked = context.getBean(DataMask.class).mask(new Customer("bruno@example.com"));
                assertThat(masked.email()).doesNotContain("bruno@example.com");
            });
        }

        @Test
        @DisplayName("prefers the configured secret when a profile also asked for an ephemeral key, because "
                + "between two configured answers the stronger one is the safe one")
        void secretWinsOverEphemeral() {
            runner.withPropertyValues("datamask.secret=" + SECRET, "datamask.ephemeral-key=true")
                    .run(context -> {
                        DataMask dataMask = context.getBean(DataMask.class);
                        // A configured key makes the pseudonym reproducible; an ephemeral one would not.
                        assertThat(dataMask.pseudonymize("CH9300762011623852957"))
                                .isEqualTo(DataMask.builder()
                                        .secret(SECRET)
                                        .build()
                                        .pseudonymize("CH9300762011623852957"));
                    });
        }

        @Test
        @DisplayName("rejects a secret too short to pseudonymise with, rather than padding it into one")
        void rejectsAShortSecret() {
            runner.withPropertyValues("datamask.secret=short")
                    .run(context -> assertThat(context)
                            .hasFailed()
                            .getFailure()
                            .rootCause()
                            .isInstanceOf(IllegalArgumentException.class));
        }
    }

    @Nested
    @DisplayName("The switch")
    class Enabled {

        @Test
        @DisplayName("datamask.enabled=false leaves the context with no masking at all, and does not fail "
                + "over the missing secret it no longer needs")
        void disabled() {
            runner.withPropertyValues("datamask.enabled=false")
                    .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(DataMask.class));
        }
    }

    @Nested
    @DisplayName("The policy")
    class Policy {

        @Test
        @DisplayName("defaults to strict, which is the production shape: everything annotated is masked and "
                + "free text is scanned")
        void defaultsToStrict() {
            runner.withPropertyValues("datamask.secret=" + SECRET).run(context -> {
                var policy = context.getBean(DataMask.class).policy();
                assertThat(policy.threshold()).isEqualTo(Sensitivity.LOW);
                assertThat(policy.scanUnannotatedText()).isTrue();
                assertThat(policy.failureMode()).isEqualTo(FailureMode.REDACT);
            });
        }

        @Test
        @DisplayName("takes the relaxed preset as a base when asked, so a developer sandbox stays readable")
        void relaxedPreset() {
            runner.withPropertyValues("datamask.secret=" + SECRET, "datamask.policy.preset=RELAXED")
                    .run(context -> {
                        var policy = context.getBean(DataMask.class).policy();
                        assertThat(policy.threshold()).isEqualTo(Sensitivity.HIGH);
                        assertThat(policy.scanUnannotatedText()).isFalse();
                    });
        }

        @Test
        @DisplayName(
                "lets a single property override the preset it sits on, leaving the rest of the preset " + "intact")
        void individualOverridesApplyOnTopOfThePreset() {
            runner.withPropertyValues(
                            "datamask.secret=" + SECRET,
                            "datamask.policy.preset=RELAXED",
                            "datamask.policy.scan-unannotated-text=true",
                            "datamask.policy.redaction-placeholder=[redacted]",
                            "datamask.policy.max-depth=8",
                            "datamask.policy.max-collection-elements=5",
                            "datamask.policy.mask-map-keys=true",
                            "datamask.policy.failure-mode=THROW")
                    .run(context -> {
                        var policy = context.getBean(DataMask.class).policy();
                        assertThat(policy.threshold()).isEqualTo(Sensitivity.HIGH);
                        assertThat(policy.scanUnannotatedText()).isTrue();
                        assertThat(policy.redactionPlaceholder()).isEqualTo("[redacted]");
                        assertThat(policy.maxDepth()).isEqualTo(8);
                        assertThat(policy.maxCollectionElements()).isEqualTo(5);
                        assertThat(policy.maskMapKeys()).isTrue();
                        assertThat(policy.failureMode()).isEqualTo(FailureMode.THROW);
                    });
        }

        @Test
        @DisplayName("still starts on PASS_THROUGH, which exists for local debugging, so the warning is the "
                + "only thing standing between it and production")
        void passThroughStartsWithAWarning() {
            runner.withPropertyValues("datamask.secret=" + SECRET, "datamask.policy.failure-mode=PASS_THROUGH")
                    .run(context -> assertThat(
                                    context.getBean(DataMask.class).policy().failureMode())
                            .isEqualTo(FailureMode.PASS_THROUGH));
        }
    }

    @Nested
    @DisplayName("What the application contributes")
    class Contributions {

        @Test
        @DisplayName("uses a TokenVault bean, so TOKENIZE can be reversed from wherever the application "
                + "keeps its surrogates")
        void tokenVaultBean() {
            runner.withPropertyValues("datamask.secret=" + SECRET)
                    .withUserConfiguration(RecordingVault.class)
                    .run(context -> {
                        DataMask dataMask = context.getBean(DataMask.class);
                        assertThat(dataMask.vault()).isSameAs(context.getBean(TokenVault.class));
                    });
        }

        @Test
        @DisplayName("applies a PolicyOverrides bean, which is the only way to mask a generated DTO nobody "
                + "can annotate")
        void policyOverridesBean() {
            runner.withPropertyValues("datamask.secret=" + SECRET)
                    .withUserConfiguration(Overrides.class)
                    .run(context -> {
                        Unannotated masked = context.getBean(DataMask.class).mask(new Unannotated("4111111111111111"));
                        assertThat(masked.reference()).doesNotContain("4111111111111111");
                    });
        }

        @Test
        @DisplayName("adds a PiiDetector bean to the default set rather than replacing it, so a house "
                + "identifier is recognised without losing IBANs")
        void detectorBean() {
            runner.withPropertyValues("datamask.secret=" + SECRET)
                    .withUserConfiguration(HouseReferenceDetector.class)
                    .run(context -> {
                        String masked = context.getBean(DataMask.class).maskText("customer HR-9982 called");
                        assertThat(masked).doesNotContain("HR-9982");
                    });
        }

        @Test
        @DisplayName("registers a Masker bean, so a custom implementation without a no-argument constructor "
                + "can still be named from an annotation")
        void maskerBean() {
            runner.withPropertyValues("datamask.secret=" + SECRET)
                    .withUserConfiguration(ShoutingMaskerConfiguration.class)
                    .run(context -> {
                        WithCustomMasker masked =
                                context.getBean(DataMask.class).mask(new WithCustomMasker("bruno@example.com"));
                        assertThat(masked.value()).isEqualTo("HIDDEN");
                    });
        }

        @Test
        @DisplayName("fans events out to every MaskingObserver bean, because the one most likely to be "
                + "dropped is the alert on unannotated PII")
        void severalObserverBeans() {
            runner.withPropertyValues("datamask.secret=" + SECRET)
                    .withUserConfiguration(TwoObservers.class)
                    .run(context -> {
                        context.getBean(DataMask.class).mask(new Customer("bruno@example.com"));
                        assertThat(context.getBean("first", CountingObserver.class).masked)
                                .isNotEmpty();
                        assertThat(context.getBean("second", CountingObserver.class).masked)
                                .isNotEmpty();
                    });
        }

        @Test
        @DisplayName("gives a DataMaskBuilderCustomizer the last word, which is how a key fetched from a KMS "
                + "gets in without going through the environment")
        void builderCustomizer() {
            runner.withPropertyValues("datamask.secret=" + SECRET)
                    .withUserConfiguration(PlaceholderCustomizer.class)
                    .run(context -> assertThat(
                                    context.getBean(DataMask.class).policy().redactionPlaceholder())
                            .isEqualTo("<gone>"));
        }

        @Test
        @DisplayName("stands aside entirely for an application that built its own DataMask")
        void backsOffForAnApplicationBean() {
            DataMask own = DataMask.builder().secret(SECRET).build();
            runner.withBean(DataMask.class, () -> own)
                    .run(context -> assertThat(context.getBean(DataMask.class)).isSameAs(own));
        }

        @Test
        @DisplayName("exposes the engine too, because that is what an integration written by hand takes")
        void engineBean() {
            runner.withPropertyValues("datamask.secret=" + SECRET)
                    .run(context -> assertThat(context.getBean(MaskingEngine.class))
                            .isSameAs(context.getBean(DataMask.class).engine()));
        }
    }

    // --- fixtures ------------------------------------------------------------------------------

    record Customer(@PII(category = PiiCategory.EMAIL) String email) {}

    record Unannotated(String reference) {}

    record WithCustomMasker(
            @PII(masker = ShoutingMasker.class) String value) {}

    static final class ShoutingMasker implements Masker {

        private final String replacement;

        ShoutingMasker(String replacement) {
            this.replacement = replacement;
        }

        @Override
        public Object mask(Object value, MaskContext context) {
            return replacement;
        }
    }

    static final class CountingObserver implements MaskingObserver {

        final List<String> masked = new ArrayList<>();

        @Override
        public void onMasked(String path, PiiCategory category, MaskStrategy strategy) {
            masked.add(path);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class RecordingVault {

        @Bean
        TokenVault vault() {
            Map<String, String> issued = new ConcurrentHashMap<>();
            return new TokenVault() {
                @Override
                public String tokenize(String value, PiiCategory category) {
                    String token = "tok-" + issued.size();
                    issued.put(token, value);
                    return token;
                }

                @Override
                public Optional<String> detokenize(String token) {
                    return Optional.ofNullable(issued.get(token));
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Overrides {

        @Bean
        PolicyOverrides overrides() {
            return new PolicyOverrides(
                    Map.of(Unannotated.class.getName() + "#reference", PiiDescriptor.redacting(PiiCategory.PAN)),
                    Map.of());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class HouseReferenceDetector {

        @Bean
        PiiDetector houseReference() {
            return new PiiDetector() {
                @Override
                public String name() {
                    return "house-reference";
                }

                @Override
                public List<PiiFinding> detect(CharSequence text) {
                    int at = text.toString().indexOf("HR-");
                    return at < 0
                            ? List.of()
                            : List.of(new PiiFinding(at, at + 7, PiiCategory.CUSTOMER_ID, name(), true));
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ShoutingMaskerConfiguration {

        @Bean
        Masker shoutingMasker() {
            return new ShoutingMasker("HIDDEN");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TwoObservers {

        @Bean
        CountingObserver first() {
            return new CountingObserver();
        }

        @Bean
        CountingObserver second() {
            return new CountingObserver();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class PlaceholderCustomizer {

        @Bean
        DataMaskBuilderCustomizer customizer() {
            return builder -> builder.policy(MaskingPolicy.strict().withRedactionPlaceholder("<gone>"));
        }
    }
}
