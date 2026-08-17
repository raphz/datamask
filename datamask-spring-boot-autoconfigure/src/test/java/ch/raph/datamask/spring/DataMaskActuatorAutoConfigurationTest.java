package ch.raph.datamask.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.endpoint.SanitizableData;
import org.springframework.boot.actuate.endpoint.Sanitizer;
import org.springframework.boot.actuate.endpoint.SanitizingFunction;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The secret is the one key that reverses every pseudonym this library issues, so the interesting
 * case is not the default — Actuator hides values then anyway — but
 * {@code management.endpoint.env.show-values: always}, which plenty of internal deployments set.
 * With it on, {@code Sanitizer} consults the registered {@link SanitizingFunction}s and prints
 * whatever none of them claimed.
 */
@DisplayName("DataMask's own secret is not readable over the Actuator endpoints that print configuration")
class DataMaskActuatorAutoConfigurationTest {

    /** Twenty-two bytes, so MaskKey would accept it. Nothing in this file is a real secret. */
    private static final String SECRET = "test-secret-0123456789";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataMaskActuatorAutoConfiguration.class));

    /** Exactly what both endpoints hold, with {@code showUnsanitized} set as {@code show-values: always} sets it. */
    private static Object shown(String key, Object value) {
        Sanitizer sanitizer = new Sanitizer(List.of(new MaskSecretSanitizingFunction()));
        return sanitizer.sanitize(new SanitizableData(null, key, value), true);
    }

    @Nested
    @DisplayName("The rule")
    class Rule {

        @Test
        @DisplayName("hides datamask.secret even when show-values is always, which is the only setting under "
                + "which the endpoint would have printed it")
        void hidesTheSecret() {
            Object value = shown("datamask.secret", SECRET);

            assertThat(String.valueOf(value)).doesNotContain(SECRET);
            assertThat(value).isEqualTo(SanitizableData.SANITIZED_VALUE);
        }

        @Test
        @DisplayName("covers the spellings relaxed binding produces, because /env reports the key the property "
                + "source used and a secret usually arrives as an environment variable")
        void coversRelaxedSpellings() {
            for (String key : List.of("DATAMASK_SECRET", "datamask-secret", "Datamask.Secret")) {
                assertThat(String.valueOf(shown(key, SECRET)))
                        .as(key)
                        .doesNotContain(SECRET);
            }
        }

        @Test
        @DisplayName("covers key material this library has not shipped yet, because the failure of a rule "
                + "listing one property name is silent")
        void coversFutureKeyMaterial() {
            assertThat(MaskSecretSanitizingFunction.isKeyMaterial("datamask.vault.token"))
                    .isTrue();
            assertThat(MaskSecretSanitizingFunction.isKeyMaterial("datamask.jdbc.password"))
                    .isTrue();
        }

        @Test
        @DisplayName("leaves datamask.ephemeral-key readable: it ends in 'key' but holds a boolean, and whether "
                + "a deployment is on a random per-JVM key is what an operator opens /env to find out")
        void leavesTheEphemeralFlagReadable() {
            assertThat(shown("datamask.ephemeral-key", "true")).isEqualTo("true");
            assertThat(shown("DATAMASK_EPHEMERAL_KEY", "true")).isEqualTo("true");
        }

        @Test
        @DisplayName("claims nothing outside the datamask prefix, so it cannot quietly blank another "
                + "library's configuration")
        void leavesEverythingElseAlone() {
            assertThat(shown("datamask.policy.preset", "STRICT")).isEqualTo("STRICT");
            assertThat(shown("spring.application.name", "billing")).isEqualTo("billing");
            assertThat(shown("some.other.secret", "value")).isEqualTo("value");
        }
    }

    @Nested
    @DisplayName("The wiring")
    class Wiring {

        @Test
        @DisplayName("declares the function as a bean, which is all either endpoint needs to pick it up")
        void declaresTheFunction() {
            runner.run(context -> assertThat(context).hasSingleBean(SanitizingFunction.class));
        }

        @Test
        @DisplayName("registers it whether or not masking itself is switched on, because datamask.enabled=false "
                + "removes the DataMask bean and not the secret sitting in the environment")
        void independentOfTheMaskingSwitch() {
            runner.withPropertyValues("datamask.enabled=false")
                    .run(context -> assertThat(context).hasSingleBean(SanitizingFunction.class));
        }

        @Test
        @DisplayName("does nothing when the Actuator is not on the classpath, so the module still works "
                + "without it")
        void absentWithoutTheActuator() {
            runner.withClassLoader(new FilteredClassLoader(SanitizingFunction.class))
                    .run(context -> assertThat(context)
                            .hasNotFailed()
                            .doesNotHaveBean(DataMaskActuatorAutoConfiguration.class));
        }
    }
}
