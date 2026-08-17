package ch.raph.datamask.domain;

import static org.assertj.core.api.Assertions.assertThat;

import ch.raph.datamask.api.MaskStrategy;
import ch.raph.datamask.api.Masker;
import ch.raph.datamask.api.PiiCategory;
import ch.raph.datamask.api.Sensitivity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The two domain types an integration has to construct by hand, and how they are meant to be built.
 *
 * <p>Both were traps. {@code PiiDescriptor} has eight positional components — including an
 * {@code int} beside a {@code char}, so a transposition compiles — and adding a ninth would break
 * every caller. {@code MaskingException} had two constructors, one taking a path first and one a
 * message first, so the wrong one silently turned a path into part of a message.
 */
@DisplayName("Building the domain types")
class VocabularyTest {

    @Nested
    @DisplayName("PiiDescriptor")
    class Descriptors {

        @Test
        @DisplayName("of(category) is the bare @PII declaration: everything left to the category")
        void ofLeavesEverythingToTheCategory() {
            PiiDescriptor descriptor = PiiDescriptor.of(PiiCategory.EMAIL);

            assertThat(descriptor.category()).isEqualTo(PiiCategory.EMAIL);
            assertThat(descriptor.strategy()).isEqualTo(MaskStrategy.AUTO);
            assertThat(descriptor.keep()).isEqualTo(-1);
            assertThat(descriptor.hasCustomMasker()).isFalse();
        }

        @Test
        @DisplayName("has a wither for every component, so nothing forces the canonical constructor")
        void everyComponentHasAWither() {
            PiiDescriptor descriptor = PiiDescriptor.of(PiiCategory.IBAN)
                    .withStrategy(MaskStrategy.HASH)
                    .withCategory(PiiCategory.ACCOUNT_NUMBER)
                    .withSensitivity(Sensitivity.LOW)
                    .withKeep(4)
                    .withPadding('#')
                    .withReplacement("[gone]")
                    .withMasker(Masker.class)
                    .withPurpose("export");

            assertThat(descriptor.strategy()).isEqualTo(MaskStrategy.HASH);
            assertThat(descriptor.category()).isEqualTo(PiiCategory.ACCOUNT_NUMBER);
            assertThat(descriptor.sensitivity()).isEqualTo(Sensitivity.LOW);
            assertThat(descriptor.keep()).isEqualTo(4);
            assertThat(descriptor.padding()).isEqualTo('#');
            assertThat(descriptor.replacement()).isEqualTo("[gone]");
            assertThat(descriptor.purpose()).isEqualTo("export");
        }

        @Test
        @DisplayName("still forces keep = 0 through a wither, because that rule lives in the constructor "
                + "every route shares")
        void withersCannotEscapeTheNeverRevealRule() {
            PiiDescriptor cvv =
                    PiiDescriptor.of(PiiCategory.CARD_VERIFICATION_VALUE).withKeep(3);

            assertThat(cvv.keep()).isZero();
            assertThat(cvv.effectiveKeep()).isZero();
            assertThat(cvv.sensitivity()).isEqualTo(Sensitivity.CRITICAL);
        }

        @Test
        @DisplayName("redacting(category) still forces REDACT, as the fail-closed fallback")
        void redactingIsUnchanged() {
            assertThat(PiiDescriptor.redacting(PiiCategory.UNSPECIFIED).strategy())
                    .isEqualTo(MaskStrategy.REDACT);
        }
    }

    @Nested
    @DisplayName("MaskingException")
    class Exceptions {

        @Test
        @DisplayName("atPath keeps the path readable rather than folding it into the message")
        void atPathKeepsThePath() {
            MaskingException failure = MaskingException.atPath("kafka:value/payments", "could not build a masked copy");

            assertThat(failure.path()).isEqualTo("kafka:value/payments");
            assertThat(failure.getMessage()).contains("kafka:value/payments").contains("could not build a masked copy");
        }

        @Test
        @DisplayName("carries the cause when there is one")
        void atPathCarriesTheCause() {
            IllegalStateException cause = new IllegalStateException("underlying");

            MaskingException failure = MaskingException.atPath("Customer.iban", "masker failed", cause);

            assertThat(failure).hasCause(cause);
        }

        @Test
        @DisplayName("withoutPath is for a failure with no position — a missing algorithm, not a bad field")
        void withoutPathHasNoPath() {
            MaskingException failure = MaskingException.withoutPath("HMAC-SHA-256 is unavailable in this JVM");

            assertThat(failure.path()).isEmpty();
            assertThat(failure.getMessage()).isEqualTo("HMAC-SHA-256 is unavailable in this JVM");
        }

        @Test
        @DisplayName("never names a value, only where and what — the exception must not become the leak")
        void namesNoValue() {
            MaskingException failure =
                    MaskingException.atPath("Customer.iban", "could not rebuild ch.acme.Customer", null);

            assertThat(failure.getMessage()).doesNotContain("CH93");
        }
    }
}
