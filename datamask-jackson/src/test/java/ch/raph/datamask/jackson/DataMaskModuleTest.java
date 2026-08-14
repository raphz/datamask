package ch.raph.datamask.jackson;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.raph.datamask.api.MaskStrategy;
import ch.raph.datamask.api.PiiCategory;
import ch.raph.datamask.application.DataMask;
import ch.raph.datamask.domain.FailureMode;
import ch.raph.datamask.domain.MaskingException;
import ch.raph.datamask.domain.MaskingObserver;
import ch.raph.datamask.domain.MaskingPolicy;
import ch.raph.datamask.jackson.testdomain.Payments;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("Masking at serialization time")
class DataMaskModuleTest {

    private static final String IBAN = "CH9300762011623852957";
    private static final String EMAIL = "john.doe@example.com";
    private static final String CARD = "4111111111111111";

    private final DataMask dataMask =
            DataMask.builder().secret("a-test-secret-of-sufficient-length").build();

    private final ObjectMapper mapper =
            JsonMapper.builder().addModule(new DataMaskModule(dataMask)).build();

    private static ObjectMapper mapperWith(DataMask dataMask) {
        return JsonMapper.builder().addModule(new DataMaskModule(dataMask)).build();
    }

    @Nested
    @DisplayName("Declared PII")
    class Declared {

        @Test
        @DisplayName("masks an annotated IBAN so the account number never reaches the document")
        void masksAnnotatedIban() {
            String json = mapper.writeValueAsString(new Payments.Account(IBAN, "CHF"));

            assertThat(json).doesNotContain(IBAN).contains("CH93 **** **** **** *295 7");
        }

        @Test
        @DisplayName("masks the string inside a value object and keeps it an object")
        void masksInsideValueObject() {
            Payments.Customer customer = new Payments.Customer(new Payments.Email(EMAIL), IBAN, "CH");

            String json = mapper.writeValueAsString(customer);

            assertThat(json).doesNotContain(EMAIL).contains("\"email\":{\"value\":\"j*******@e******.com\"}");
        }

        @Test
        @DisplayName("keeps only the last four digits of a card number, as PCI-DSS 3.3 requires")
        void masksCardNumber() {
            String json = mapper.writeValueAsString(new Payments.Card(CARD, "123", "John Doe"));

            assertThat(json).doesNotContain(CARD).contains("**** **** **** 1111");
        }

        @Test
        @DisplayName("redacts the CVV entirely, overriding an annotation that asked to keep three")
        void redactsCvvEntirely() {
            String json = mapper.writeValueAsString(new Payments.Card(CARD, "123", "John Doe"));

            assertThat(json).doesNotContain("123\"").contains("\"cvv\":\"****\"");
        }

        @Test
        @DisplayName("writes null for a strategy that removes the value outright")
        void nullifiesCredentials() {
            String json = mapper.writeValueAsString(new Payments.Credential("sk_live_9f3a", "treasury"));

            assertThat(json).doesNotContain("sk_live_9f3a").contains("\"apiKey\":null");
        }

        @Test
        @DisplayName("produces the same pseudonym for the same value, so a customer stays traceable")
        void hashesConsistently() {
            Payments.Customer customer = new Payments.Customer(new Payments.Email(EMAIL), IBAN, "CH");

            String first = mapper.writeValueAsString(customer);
            String second = mapper.writeValueAsString(customer);

            assertThat(first).doesNotContain(IBAN).isEqualTo(second);
        }

        @Test
        @DisplayName("masks a property Jackson renamed, because the plan is matched by member not by JSON name")
        void masksRenamedProperty() {
            String json = mapper.writeValueAsString(new Payments.Contact(EMAIL));

            assertThat(json).doesNotContain(EMAIL).isEqualTo("{\"email_address\":\"j*******@e******.com\"}");
        }

        @Test
        @DisplayName("masks a getter-based bean as readily as a record")
        void masksGetterBean() {
            String json = mapper.writeValueAsString(new Payments.LegacyCustomer(EMAIL, "CH"));

            assertThat(json)
                    .doesNotContain(EMAIL)
                    .contains("j*******@e******.com")
                    .contains("\"country\":\"CH\"");
        }

        @Test
        @DisplayName("leaves the object being serialised untouched, because the caller is still using it")
        void doesNotMutateTheSource() {
            Payments.Account account = new Payments.Account(IBAN, "CHF");

            mapper.writeValueAsString(account);

            assertThat(account.iban()).isEqualTo(IBAN);
        }
    }

    @Nested
    @DisplayName("Text nobody annotated")
    class Undeclared {

        @Test
        @DisplayName("masks an IBAN a customer typed into a free-text reference")
        void masksIbanInFreeText() {
            Payments.Payment payment = new Payments.Payment("rent for " + IBAN, null, List.of(), Map.of());

            String json = mapper.writeValueAsString(payment);

            assertThat(json).doesNotContain(IBAN).contains("rent for CH93 **** **** **** *295 7");
        }

        @Test
        @DisplayName("masks PII inside a list element, which no property-level hook would reach")
        void masksInsideListElement() {
            Payments.Payment payment =
                    new Payments.Payment("PMT-1", null, List.of("called " + EMAIL, "no PII here"), Map.of());

            String json = mapper.writeValueAsString(payment);

            assertThat(json).doesNotContain(EMAIL).contains("no PII here");
        }

        @Test
        @DisplayName("masks PII inside a map value")
        void masksInsideMapValue() {
            Payments.Payment payment = new Payments.Payment("PMT-1", null, List.of(), Map.of("contact", EMAIL));

            String json = mapper.writeValueAsString(payment);

            assertThat(json).doesNotContain(EMAIL).contains("j*******@e******.com");
        }

        @Test
        @DisplayName("leaves free text alone when the policy has content scanning switched off")
        void respectsScanningBeingOff() {
            DataMask quiet = DataMask.builder()
                    .secret("a-test-secret-of-sufficient-length")
                    .policy(MaskingPolicy.strict().withScanUnannotatedText(false))
                    .build();

            String json = mapperWith(quiet)
                    .writeValueAsString(new Payments.Payment("rent for " + IBAN, null, List.of(), Map.of()));

            assertThat(json).contains(IBAN);
        }
    }

    @Nested
    @DisplayName("@NoMask")
    class Exemptions {

        @Test
        @DisplayName("writes an exempt currency code verbatim")
        void keepsCurrencyCode() {
            String json = mapper.writeValueAsString(new Payments.Account(IBAN, "CHF"));

            assertThat(json).contains("\"currency\":\"CHF\"");
        }

        @Test
        @DisplayName("holds against the detectors too, or the exemption would only be half honoured")
        void keepsExemptValueOutOfTheScanner() {
            String json = mapper.writeValueAsString(new Payments.Reconciliation(IBAN));

            assertThat(json).isEqualTo("{\"houseIban\":\"" + IBAN + "\"}");
        }
    }

    @Nested
    @DisplayName("Policy")
    class Policies {

        @Test
        @DisplayName("masks low-sensitivity data under the strict policy")
        void strictMasksLowSensitivity() {
            String json = mapper.writeValueAsString(new Payments.LowRisk(EMAIL));

            assertThat(json).doesNotContain(EMAIL);
        }

        @Test
        @DisplayName("leaves low-sensitivity data readable under the relaxed policy, so a sandbox log stays useful")
        void relaxedKeepsLowSensitivity() {
            DataMask relaxed = DataMask.builder()
                    .secret("a-test-secret-of-sufficient-length")
                    .policy(MaskingPolicy.relaxed())
                    .build();

            String json = mapperWith(relaxed).writeValueAsString(new Payments.LowRisk(EMAIL));

            assertThat(json).contains(EMAIL);
        }

        @Test
        @DisplayName("still hides a card number under the relaxed policy")
        void relaxedStillMasksHighSensitivity() {
            DataMask relaxed = DataMask.builder()
                    .secret("a-test-secret-of-sufficient-length")
                    .policy(MaskingPolicy.relaxed())
                    .build();

            String json = mapperWith(relaxed).writeValueAsString(new Payments.Card(CARD, "123", "John Doe"));

            assertThat(json).doesNotContain(CARD);
        }
    }

    @Nested
    @DisplayName("Observation")
    class Observation {

        @Test
        @DisplayName("reports every masked property with the path and category, for the compliance record")
        void reportsMaskedProperties() {
            Recorder recorder = new Recorder();
            DataMask observed = DataMask.builder()
                    .secret("a-test-secret-of-sufficient-length")
                    .observer(recorder)
                    .build();

            mapperWith(observed).writeValueAsString(new Payments.Account(IBAN, "CHF"));

            assertThat(recorder.masked).containsExactly("Account.iban:IBAN:IBAN");
        }

        @Test
        @DisplayName("reports a detector hit on a value nobody annotated, the earliest warning that a field leaks")
        void reportsUndeclaredPii() {
            Recorder recorder = new Recorder();
            DataMask observed = DataMask.builder()
                    .secret("a-test-secret-of-sufficient-length")
                    .observer(recorder)
                    .build();

            mapperWith(observed)
                    .writeValueAsString(new Payments.Payment("rent for " + IBAN, null, List.of(), Map.of()));

            assertThat(recorder.undeclared).containsExactly("reference:IBAN");
        }
    }

    @Nested
    @DisplayName("Failing closed")
    class FailingClosed {

        @Test
        @DisplayName("writes the placeholder when a masker throws, never the value it failed to mask")
        void redactsWhenTheMaskerThrows() {
            String json = mapper.writeValueAsString(new Payments.Fragile(IBAN));

            assertThat(json).doesNotContain(IBAN).isEqualTo("{\"secret\":\"****\"}");
        }

        @Test
        @DisplayName("aborts the document under FailureMode.THROW rather than emit anything for the field")
        void abortsUnderThrow() {
            DataMask strict = DataMask.builder()
                    .secret("a-test-secret-of-sufficient-length")
                    .policy(MaskingPolicy.strict().withFailureMode(FailureMode.THROW))
                    .build();
            ObjectMapper strictMapper = mapperWith(strict);
            Payments.Fragile fragile = new Payments.Fragile(IBAN);

            // Jackson wraps the failure to add the reference chain, so the MaskingException is the
            // cause rather than the thrown type. What matters is that it names the property and
            // that neither message carries the value that failed to mask.
            Throwable thrown = catchThrowable(() -> strictMapper.writeValueAsString(fragile));

            assertThat(thrown)
                    .hasCauseInstanceOf(MaskingException.class)
                    .hasMessageContaining("Fragile.secret")
                    .hasMessageNotContaining(IBAN);
            assertThat(thrown.getCause()).hasMessageNotContaining(IBAN);
        }

        @Test
        @DisplayName(
                "masks a property that also declares its own serializer, so a custom renderer is no way around @PII")
        void maskingOutranksACustomSerializer() {
            String json = mapper.writeValueAsString(new Payments.Branded(EMAIL));

            assertThat(json)
                    .doesNotContain(EMAIL)
                    .doesNotContain(EMAIL.toUpperCase(Locale.ROOT))
                    .isEqualTo("{\"email\":\"j*******@e******.com\"}");
        }
    }

    @Nested
    @DisplayName("Reading")
    class Reading {

        @Test
        @DisplayName("leaves deserialization alone, because masking on the way in would destroy stored data")
        void doesNotMaskOnTheWayIn() {
            String json = "{\"iban\":\"" + IBAN + "\",\"currency\":\"CHF\"}";

            Payments.Account account = mapper.readValue(json, Payments.Account.class);

            assertThat(account.iban()).isEqualTo(IBAN);
        }
    }

    private static final class Recorder implements MaskingObserver {

        private final List<String> masked = new ArrayList<>();
        private final List<String> undeclared = new ArrayList<>();

        @Override
        public void onMasked(String path, PiiCategory category, MaskStrategy strategy) {
            masked.add(path + ":" + category + ":" + strategy);
        }

        @Override
        public void onUnannotatedPii(String path, PiiCategory category, String detector) {
            undeclared.add(path + ":" + category);
        }
    }
}
