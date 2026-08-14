package ch.raph.datamask.application;

import static org.assertj.core.api.Assertions.assertThat;

import ch.raph.datamask.api.MaskStrategy;
import ch.raph.datamask.api.PII;
import ch.raph.datamask.api.PiiCategory;
import ch.raph.datamask.domain.MaskingPolicy;
import ch.raph.datamask.testdomain.Banking;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("DataMask")
class DataMaskTest {

    private static final String SECRET = "a-test-secret-of-sufficient-length";

    private final DataMask dataMask = DataMask.builder().secret(SECRET).build();

    @Nested
    @DisplayName("the documented example")
    class DocumentedExample {

        private final Banking.Customer customer =
                new Banking.Customer(new Banking.Email("john.doe@example.com"), "CH9300762011623852957", "CH");

        @Test
        @DisplayName("masks the address inside the value object and keeps it a value object")
        void masksEmailValueObject() {
            Banking.Customer masked = dataMask.mask(customer);

            assertThat(masked.email()).isInstanceOf(Banking.Email.class);
            assertThat(masked.email().value()).isEqualTo("j*******@e******.com");
        }

        @Test
        @DisplayName("pseudonymises the IBAN into a surrogate that reveals nothing")
        void pseudonymisesIban() {
            Banking.Customer masked = dataMask.mask(customer);

            assertThat(masked.iban())
                    .startsWith("~")
                    .doesNotContain("9300762011623852957")
                    .hasSize(17);
        }

        @Test
        @DisplayName("leaves the unannotated country alone")
        void keepsNonPii() {
            assertThat(dataMask.mask(customer).country()).isEqualTo("CH");
        }

        @Test
        @DisplayName("never touches the object it was given")
        void doesNotMutateTheOriginal() {
            dataMask.mask(customer);

            assertThat(customer.email().value()).isEqualTo("john.doe@example.com");
            assertThat(customer.iban()).isEqualTo("CH9300762011623852957");
        }
    }

    @Nested
    @DisplayName("pseudonymisation")
    class Pseudonymisation {

        @Test
        @DisplayName("is stable across instances sharing a secret, so logs stay joinable")
        void isStableAcrossInstances() {
            DataMask other = DataMask.builder().secret(SECRET).build();

            assertThat(dataMask.pseudonymize("CH9300762011623852957"))
                    .isEqualTo(other.pseudonymize("CH9300762011623852957"));
        }

        @Test
        @DisplayName("differs under a different secret, so surrogates cannot be joined across tenants")
        void differsPerSecret() {
            DataMask other = DataMask.builder()
                    .secret("a-completely-different-secret-value")
                    .build();

            assertThat(dataMask.pseudonymize("CH9300762011623852957"))
                    .isNotEqualTo(other.pseudonymize("CH9300762011623852957"));
        }

        @Test
        @DisplayName("refuses a secret short enough to brute-force")
        void rejectsShortSecrets() {
            assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                            IllegalArgumentException.class,
                            () -> DataMask.builder().secret("changeme")))
                    .hasMessageContaining("at least 16 bytes");
        }
    }

    @Nested
    @DisplayName("card data")
    class CardData {

        @Test
        @DisplayName("masks a PAN down to its last four digits")
        void masksPan() {
            Banking.Card masked = dataMask.mask(new Banking.Card("4111111111111111", "123", "John Doe"));

            assertThat(masked.number()).isEqualTo("**** **** **** 1111");
        }

        @Test
        @DisplayName("redacts the CVV entirely, overriding an annotation that asked to keep three")
        void neverPartiallyRevealsCvv() {
            Banking.Card masked = dataMask.mask(new Banking.Card("4111111111111111", "123", "John Doe"));

            assertThat(masked.cvv()).isEqualTo("****").doesNotContain("1", "2", "3");
        }

        @Test
        @DisplayName("keeps initials of the cardholder name")
        void masksHolderName() {
            Banking.Card masked = dataMask.mask(new Banking.Card("4111111111111111", "123", "John Doe"));

            assertThat(masked.holder()).isEqualTo("J*** D**");
        }
    }

    @Nested
    @DisplayName("typed values")
    class TypedValues {

        @Test
        @DisplayName("keeps an IBAN readable enough to confirm over the phone")
        void masksIban() {
            Banking.Account masked =
                    dataMask.mask(new Banking.Account("CH9300762011623852957", new BigDecimal("12345.67"), "CHF"));

            assertThat(masked.iban()).isEqualTo("CH93 **** **** **** *295 7");
        }

        @Test
        @DisplayName("replaces a masked numeric field with zero rather than failing to rebuild")
        void coercesNumericFields() {
            Banking.Account masked =
                    dataMask.mask(new Banking.Account("CH9300762011623852957", new BigDecimal("12345.67"), "CHF"));

            assertThat(masked.balance()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("honours an explicit @NoMask exemption")
        void honoursExemption() {
            Banking.Account masked = dataMask.mask(new Banking.Account("CH9300762011623852957", BigDecimal.ONE, "CHF"));

            assertThat(masked.currency()).isEqualTo("CHF");
        }

        @Test
        @DisplayName("widens a birth date to its year and keeps it a LocalDate")
        void generalisesDates() {
            var masked = dataMask.mask(profile());

            assertThat(masked.birthDate()).isEqualTo(LocalDate.of(1985, 1, 1));
        }

        @Test
        @DisplayName("masks a Swiss AVS number completely")
        void masksNationalId() {
            assertThat(dataMask.mask(profile()).avs()).isEqualTo("***.****.****.**");
        }

        @Test
        @DisplayName("keeps the country prefix and last two digits of a phone number")
        void masksPhone() {
            assertThat(dataMask.mask(profile()).phone()).isEqualTo("+41*******67");
        }

        @Test
        @DisplayName("drops the host portion of an IP address")
        void masksIpAddress() {
            assertThat(dataMask.mask(profile()).lastLoginIp()).isEqualTo("192.168.4.0");
        }

        @Test
        @DisplayName("hashes a customer id so requests stay correlatable")
        void hashesCustomerId() {
            assertThat(dataMask.mask(profile()).customerId())
                    .startsWith("~")
                    .isEqualTo(dataMask.pseudonymize("CUST-000123"));
        }

        @Test
        @DisplayName("redacts a credential outright")
        void redactsCredentials() {
            assertThat(dataMask.mask(profile()).apiKey()).isEqualTo("****");
        }

        @Test
        @DisplayName("masks only the identifiers inside free-form prose")
        void scansFreeformText() {
            String note = dataMask.mask(profile()).note();

            assertThat(note)
                    .startsWith("Customer called about IBAN ")
                    .contains("CH93 **** **** **** *295 7")
                    .contains("**** **** **** 1111")
                    .doesNotContain("9300762011623852957")
                    .doesNotContain("4111111111111111");
        }
    }

    @Nested
    @DisplayName("policy")
    class Policy {

        @Test
        @DisplayName("leaves low-sensitivity data alone when the threshold is high")
        void respectsThreshold() {
            DataMask relaxed = DataMask.builder()
                    .secret(SECRET)
                    .policy(MaskingPolicy.relaxed())
                    .build();

            assertThat(relaxed.mask(new Banking.LowRisk("john@example.com")).email())
                    .isEqualTo("john@example.com");
        }

        @Test
        @DisplayName("masks the same field under the strict default")
        void strictMasksEverything() {
            assertThat(dataMask.mask(new Banking.LowRisk("john@example.com")).email())
                    .isEqualTo("j***@e******.com");
        }

        @Test
        @DisplayName("does not scan unannotated text when scanning is off")
        void scanningIsOptional() {
            DataMask relaxed = DataMask.builder()
                    .secret(SECRET)
                    .policy(MaskingPolicy.relaxed())
                    .build();

            record Ticket(String body) {}
            assertThat(relaxed.mask(new Ticket("call 4111111111111111")).body()).isEqualTo("call 4111111111111111");
        }

        @Test
        @DisplayName("catches an unannotated card number when scanning is on")
        void scanningCatchesUnannotatedPii() {
            record Ticket(String body) {}
            assertThat(dataMask.mask(new Ticket("call 4111111111111111")).body())
                    .isEqualTo("call **** **** **** 1111");
        }
    }

    @Nested
    @DisplayName("tokenisation")
    class Tokenisation {

        record Payment(
                @PII(strategy = MaskStrategy.TOKENIZE, category = PiiCategory.IBAN)
                String debtor) {}

        @Test
        @DisplayName("issues a surrogate the vault can exchange back")
        void roundTrips() {
            Payment masked = dataMask.mask(new Payment("CH9300762011623852957"));

            assertThat(masked.debtor()).startsWith("tok_iban_");
            assertThat(dataMask.detokenize(masked.debtor())).contains("CH9300762011623852957");
        }

        @Test
        @DisplayName("issues the same surrogate for the same value")
        void isStableWithinAVault() {
            assertThat(dataMask.mask(new Payment("CH9300762011623852957")).debtor())
                    .isEqualTo(
                            dataMask.mask(new Payment("CH9300762011623852957")).debtor());
        }
    }

    private static Banking.Profile profile() {
        return new Banking.Profile(
                "Jean-Pierre Dubois",
                LocalDate.of(1985, 3, 17),
                "756.9217.0769.85",
                "+41791234567",
                "192.168.4.37",
                "CUST-000123",
                "Customer called about IBAN CH9300762011623852957 and card 4111111111111111",
                "sk_live_abcdef0123456789",
                "PRIVATE");
    }
}
