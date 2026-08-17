package ch.raph.datamask.jackson;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.raph.datamask.api.MaskStrategy;
import ch.raph.datamask.api.PiiCategory;
import ch.raph.datamask.api.Sensitivity;
import ch.raph.datamask.application.DataMask;
import ch.raph.datamask.domain.FailureMode;
import ch.raph.datamask.domain.MaskingException;
import ch.raph.datamask.domain.MaskingObserver;
import ch.raph.datamask.domain.MaskingPolicy;
import ch.raph.datamask.domain.PolicyOverrides;
import ch.raph.datamask.jackson.testdomain.Payments;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

@DisplayName("Masking at serialization time")
class DataMaskModuleTest {

    private static final String SECRET = "a-test-secret-of-sufficient-length";
    private static final String IBAN = "CH9300762011623852957";
    private static final String EMAIL = "john.doe@example.com";
    private static final String CARD = "4111111111111111";

    private final DataMask dataMask = DataMask.builder().secret(SECRET).build();

    private final ObjectMapper mapper =
            JsonMapper.builder().addModule(new DataMaskModule(dataMask)).build();

    private static ObjectMapper mapperWith(DataMask dataMask) {
        return JsonMapper.builder().addModule(new DataMaskModule(dataMask)).build();
    }

    /**
     * A DataMask configured to drop one member, which is the route a deployment actually takes:
     * {@code PolicyOverrides.builder().drop(...)} compiles to {@code MaskAction.Drop}, and this
     * module is what honours it by leaving the property out of the document altogether.
     *
     * <p>The builder falls back to the reflective compiler whenever overrides are present, so the
     * generated-plan path is deliberately not involved.
     */
    private static DataMask withDropped(Class<?> type, String member) {
        return DataMask.builder()
                .secret(SECRET)
                .overrides(PolicyOverrides.builder().drop(type, member).build())
                .build();
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
                    .secret(SECRET)
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
    @DisplayName("Dropped members")
    class Dropped {

        @Test
        @DisplayName("omits a member the deployment dropped, leaving no trace that the field exists")
        void omitsADroppedMember() {
            DataMask dropping = withDropped(Payments.Payment.class, "reference");

            String json = mapperWith(dropping)
                    .writeValueAsString(new Payments.Payment("rent for " + IBAN, null, List.of(), Map.of()));

            assertThat(json).doesNotContain("reference").doesNotContain(IBAN).contains("\"notes\":[]");
        }

        @Test
        @DisplayName("drops ahead of a @NoMask exemption, because the deployment is the one being audited")
        void dropsAheadOfAnExemption() {
            DataMask dropping = withDropped(Payments.Account.class, "currency");

            String json = mapperWith(dropping).writeValueAsString(new Payments.Account(IBAN, "CHF"));

            assertThat(json).doesNotContain("currency").doesNotContain("CHF").contains("\"iban\"");
        }
    }

    @Nested
    @DisplayName("Map keys")
    class MapKeys {

        @Test
        @DisplayName("masks an IBAN used as a map key, which is written through a lookup of its own")
        void masksAKeyCarryingPii() {
            Payments.Payment payment = new Payments.Payment("PMT-1", null, List.of(), Map.of(IBAN, "42.00"));

            String json = mapper.writeValueAsString(payment);

            assertThat(json).doesNotContain(IBAN).contains("CH93 **** **** **** *295 7");
        }

        @Test
        @DisplayName("masks keys under a policy that asks only for that, leaving free text alone")
        void masksKeysWhenOnlyKeyMaskingIsOn() {
            DataMask keysOnly = DataMask.builder()
                    .secret(SECRET)
                    .policy(new MaskingPolicy(Sensitivity.LOW, FailureMode.REDACT, "****", 32, 1_000, false, true))
                    .build();
            Payments.Payment payment = new Payments.Payment("rent for " + IBAN, null, List.of(), Map.of(IBAN, "42.00"));

            String json = mapperWith(keysOnly).writeValueAsString(payment);

            assertThat(json).contains("rent for " + IBAN).contains("\"CH93 **** **** **** *295 7\":\"42.00\"");
        }

        @Test
        @DisplayName("never lets a key reach the path an observer sees, because the key is often the PII itself")
        void keepsKeysOutOfObserverPaths() {
            Recorder recorder = new Recorder();
            DataMask observed =
                    DataMask.builder().secret(SECRET).observer(recorder).build();
            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put(IBAN, "42.00");
            attributes.put(EMAIL, "owner");

            mapperWith(observed).writeValueAsString(new Payments.Payment("PMT-1", null, List.of(), attributes));

            assertThat(recorder.undeclared)
                    .isNotEmpty()
                    .allSatisfy(path -> assertThat(path).doesNotContain(IBAN).doesNotContain(EMAIL));
        }

        @Test
        @DisplayName("leaves keys alone when neither key masking nor scanning is on")
        void leavesKeysAloneWhenBothAreOff() {
            DataMask relaxed = DataMask.builder()
                    .secret(SECRET)
                    .policy(MaskingPolicy.relaxed())
                    .build();
            Payments.Payment payment = new Payments.Payment("PMT-1", null, List.of(), Map.of(IBAN, "42.00"));

            String json = mapperWith(relaxed).writeValueAsString(payment);

            assertThat(json).contains("\"" + IBAN + "\":\"42.00\"");
        }
    }

    @Nested
    @DisplayName("Trees and other CharSequences")
    class Trees {

        @Test
        @DisplayName("masks PII nested inside a JsonNode tree, which writes itself and never asks for a serializer")
        void masksInsideAJsonTree() {
            ObjectNode payload = JsonNodeFactory.instance.objectNode();
            payload.putObject("customer").put("note", "called " + EMAIL);
            payload.putArray("references").add("rent for " + IBAN);

            String json = mapper.writeValueAsString(new Payments.Webhook("wh-1", payload));

            assertThat(json)
                    .doesNotContain(EMAIL)
                    .doesNotContain(IBAN)
                    .contains("j*******@e******.com")
                    .contains("CH93 **** **** **** *295 7");
        }

        @Test
        @DisplayName("masks a property name inside a tree, the same way it masks a map key")
        void masksAFieldNameInsideAJsonTree() {
            ObjectNode payload = JsonNodeFactory.instance.objectNode();
            payload.put(EMAIL, "owner");

            String json = mapper.writeValueAsString(new Payments.Webhook("wh-1", payload));

            assertThat(json).doesNotContain(EMAIL).contains("\"j*******@e******.com\":\"owner\"");
        }

        @Test
        @DisplayName("masks a CharSequence that is not a String, which Jackson writes by another route")
        void masksANonStringCharSequence() {
            String json = mapper.writeValueAsString(new Payments.Note(new StringBuilder("rent for " + IBAN)));

            assertThat(json).doesNotContain(IBAN).contains("rent for CH93 **** **** **** *295 7");
        }
    }

    @Nested
    @DisplayName("@JsonUnwrapped")
    class Unwrapping {

        @Test
        @DisplayName("masks a property that was flattened into its holder")
        void masksAnUnwrappedProperty() {
            Payments.Order order = new Payments.Order("ord-1", new Payments.Party(EMAIL, IBAN, "REF-1"));

            String json = mapper.writeValueAsString(order);

            assertThat(json).doesNotContain(EMAIL).contains("\"email\":\"j*******@e******.com\"");
        }

        @Test
        @DisplayName("masks it under a prefix too, which makes Jackson rebuild every property writer")
        void masksAnUnwrappedPropertyUnderAPrefix() {
            Payments.PrefixedOrder order = new Payments.PrefixedOrder(new Payments.Party(EMAIL, IBAN, "REF-1"));

            String json = mapper.writeValueAsString(order);

            assertThat(json).doesNotContain(EMAIL).contains("\"party_email\":\"j*******@e******.com\"");
        }

        @Test
        @DisplayName("carries the @NoMask exemption through the flattening, scanner included")
        void keepsAnUnwrappedExemptionVerbatim() {
            Payments.Order order = new Payments.Order("ord-1", new Payments.Party(EMAIL, IBAN, "REF-1"));

            String json = mapper.writeValueAsString(order);

            assertThat(json).contains("\"houseIban\":\"" + IBAN + "\"");
        }

        @Test
        @DisplayName("still omits a dropped property once it is flattened, leaving no trace of the field")
        void dropsAnUnwrappedProperty() {
            DataMask dropping = withDropped(Payments.Party.class, "reference");
            Payments.Order order = new Payments.Order("ord-1", new Payments.Party(EMAIL, IBAN, "REF-1"));

            String json = mapperWith(dropping).writeValueAsString(order);

            assertThat(json).doesNotContain("REF-1").doesNotContain("reference");
        }
    }

    @Nested
    @DisplayName("Polymorphism")
    class Polymorphism {

        @Test
        @DisplayName("masks inside a polymorphic property and keeps the type id")
        void masksInsideAPolymorphicProperty() {
            Payments.Wallet wallet = new Payments.Wallet(new Payments.CardInstrument(CARD), List.of());

            String json = mapper.writeValueAsString(wallet);

            assertThat(json).doesNotContain(CARD).contains("\"type\":\"card\"").contains("**** **** **** 1111");
        }

        @Test
        @DisplayName("masks inside polymorphic collection elements, which carry their own type id")
        void masksInsidePolymorphicElements() {
            Payments.Wallet wallet =
                    new Payments.Wallet(new Payments.CardInstrument(CARD), List.of(new Payments.BankInstrument(IBAN)));

            String json = mapper.writeValueAsString(wallet);

            assertThat(json)
                    .doesNotContain(IBAN)
                    .doesNotContain(CARD)
                    .contains("\"type\":\"bank\"")
                    .contains("CH93 **** **** **** *295 7");
        }

        @Test
        @DisplayName("masks a polymorphic value written as the root of the document")
        void masksARootPolymorphicValue() {
            String json = mapper.writeValueAsString(new Payments.BankInstrument(IBAN));

            assertThat(json).doesNotContain(IBAN).contains("\"type\":\"bank\"");
        }

        @Test
        @DisplayName("masks a property that is both classified and polymorphic, before the type serializer runs")
        void masksAClassifiedPolymorphicProperty() {
            String json = mapper.writeValueAsString(new Payments.Envelope(EMAIL));

            assertThat(json).doesNotContain(EMAIL).contains("j*******@e******.com");
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
                    .secret(SECRET)
                    .policy(MaskingPolicy.relaxed())
                    .build();

            String json = mapperWith(relaxed).writeValueAsString(new Payments.LowRisk(EMAIL));

            assertThat(json).contains(EMAIL);
        }

        @Test
        @DisplayName("still hides a card number under the relaxed policy")
        void relaxedStillMasksHighSensitivity() {
            DataMask relaxed = DataMask.builder()
                    .secret(SECRET)
                    .policy(MaskingPolicy.relaxed())
                    .build();

            String json = mapperWith(relaxed).writeValueAsString(new Payments.Card(CARD, "123", "John Doe"));

            assertThat(json).doesNotContain(CARD);
        }
    }

    @Nested
    @DisplayName("Observation")
    class Observation {

        private final Recorder recorder = new Recorder();

        private final ObjectMapper observed =
                mapperWith(DataMask.builder().secret(SECRET).observer(recorder).build());

        @Test
        @DisplayName("reports every masked property with the path and category, for the compliance record")
        void reportsMaskedProperties() {
            observed.writeValueAsString(new Payments.Account(IBAN, "CHF"));

            assertThat(recorder.masked).containsExactly("jackson:Account/iban:IBAN:IBAN");
        }

        @Test
        @DisplayName("reports a detector hit on a value nobody annotated, the earliest warning that a field leaks")
        void reportsUndeclaredPii() {
            observed.writeValueAsString(new Payments.Payment("rent for " + IBAN, null, List.of(), Map.of()));

            assertThat(recorder.undeclared).containsExactly("jackson:text/reference:IBAN");
        }

        @Test
        @DisplayName("names the map-key site, which is a different hole from a value carrying PII")
        void namesTheMapKeySite() {
            observed.writeValueAsString(new Payments.Payment("PMT-1", null, List.of(), Map.of(IBAN, "42.00")));

            assertThat(recorder.undeclared).containsExactly("jackson:key/attributes:IBAN");
        }

        @Test
        @DisplayName("names the tree site, so an untyped payload is distinguishable from a typed property")
        void namesTheTreeSite() {
            ObjectNode payload = JsonNodeFactory.instance.objectNode();
            payload.putObject("customer").put("note", "called " + EMAIL);

            observed.writeValueAsString(new Payments.Webhook("wh-1", payload));

            assertThat(recorder.undeclared).containsExactly("jackson:tree/payload:EMAIL");
        }

        @Test
        @DisplayName("marks a name inside a tree as a key, the way the engine marks a map key")
        void namesTheTreeKeySite() {
            ObjectNode payload = JsonNodeFactory.instance.objectNode();
            payload.put(EMAIL, "owner");

            observed.writeValueAsString(new Payments.Webhook("wh-1", payload));

            assertThat(recorder.undeclared).containsExactly("jackson:tree/payload{key}:EMAIL");
        }

        @Test
        @DisplayName("falls back to the site alone for a value written outside any property")
        void namesTheSiteAloneAtTheRoot() {
            observed.writeValueAsString("rent for " + IBAN);

            assertThat(recorder.undeclared).containsExactly("jackson:text:IBAN");
        }

        @Test
        @DisplayName("reports declared free text as a scan, so it does not dilute the unannotated-PII alert")
        void reportsDeclaredFreeTextAsAScan() {
            observed.writeValueAsString(new Payments.SupportTicket("customer quoted " + IBAN));

            assertThat(recorder.scanned).containsExactly("jackson:SupportTicket/body:IBAN");
            assertThat(recorder.undeclared).isEmpty();
        }

        @Test
        @DisplayName("prefixes every path with the module, so a rule keying on the scheme can tell sources apart")
        void prefixesEveryPathWithTheModule() {
            ObjectNode payload = JsonNodeFactory.instance.objectNode();
            payload.put(EMAIL, "called " + EMAIL);

            observed.writeValueAsString(new Payments.Account(IBAN, "CHF"));
            observed.writeValueAsString(new Payments.SupportTicket("customer quoted " + IBAN));
            observed.writeValueAsString(new Payments.Webhook("wh-1", payload));
            observed.writeValueAsString(new Payments.Payment("rent for " + IBAN, null, List.of(), Map.of(EMAIL, "x")));

            assertThat(recorder.everything())
                    .isNotEmpty()
                    .allSatisfy(path -> assertThat(path).startsWith("jackson:"));
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
                    .secret(SECRET)
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
                    .hasMessageContaining("jackson:Fragile/secret")
                    .hasMessageNotContaining(IBAN);
            assertThat(thrown.getCause()).hasMessageNotContaining(IBAN);
        }

        @Test
        @DisplayName("withholds the string when a masker the scanner reached throws, rather than fail the document")
        void redactsWhenAScannedMaskerThrows() {
            Recorder recorder = new Recorder();
            DataMask broken = DataMask.builder()
                    .secret(SECRET)
                    .observer(recorder)
                    .masker(MaskStrategy.IBAN, new Payments.BrokenMasker())
                    .build();
            Payments.Payment payment = new Payments.Payment("rent for " + IBAN, null, List.of(), Map.of());

            String json = mapperWith(broken).writeValueAsString(payment);

            assertThat(json).doesNotContain(IBAN).contains("\"reference\":\"****\"");
            assertThat(recorder.failures).containsExactly("jackson:text/reference");
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
        private final List<String> scanned = new ArrayList<>();
        private final List<String> failures = new ArrayList<>();

        @Override
        public void onMasked(String path, PiiCategory category, MaskStrategy strategy) {
            masked.add(path + ":" + category + ":" + strategy);
        }

        @Override
        public void onUnannotatedPii(String path, PiiCategory category, String detector) {
            undeclared.add(path + ":" + category);
        }

        @Override
        public void onScanned(String path, PiiCategory category, String detector) {
            scanned.add(path + ":" + category);
        }

        @Override
        public void onFailure(String path, Throwable failure) {
            failures.add(path);
        }

        /** Every path this observer was told about, whichever signal carried it. */
        private List<String> everything() {
            List<String> all = new ArrayList<>(masked);
            all.addAll(undeclared);
            all.addAll(scanned);
            all.addAll(failures);
            return all;
        }
    }
}
