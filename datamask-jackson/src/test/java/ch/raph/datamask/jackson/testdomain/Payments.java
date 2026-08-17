package ch.raph.datamask.jackson.testdomain;

import ch.raph.datamask.api.MaskContext;
import ch.raph.datamask.api.MaskStrategy;
import ch.raph.datamask.api.Masker;
import ch.raph.datamask.api.NoMask;
import ch.raph.datamask.api.PII;
import ch.raph.datamask.api.PiiCategory;
import ch.raph.datamask.api.Sensitivity;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.annotation.JsonSerialize;

/** Payloads shaped like the ones a payment service actually serialises. */
public final class Payments {

    private Payments() {}

    /** A value object carrying its own classification, so every use of it is covered. */
    @PII(category = PiiCategory.EMAIL)
    public record Email(String value) {}

    public record Customer(
            @PII Email email,
            @PII(strategy = MaskStrategy.HASH) String iban,
            String country) {}

    public record Card(
            @PII(category = PiiCategory.PAN) String number,

            @PII(category = PiiCategory.CARD_VERIFICATION_VALUE, keep = 3)
            String cvv,

            @PII(category = PiiCategory.FULL_NAME) String holder) {}

    public record Account(
            @PII(category = PiiCategory.IBAN) String iban,

            @NoMask(justification = "ISO currency code identifies no one")
            String currency) {}

    /** Free-text and container members, none of them annotated. */
    public record Payment(String reference, Customer payer, List<String> notes, Map<String, String> attributes) {}

    public record Credential(
            @PII(category = PiiCategory.CREDENTIAL, strategy = MaskStrategy.NULLIFY)
            String apiKey,

            String owner) {}

    public record LowRisk(
            @PII(sensitivity = Sensitivity.LOW, category = PiiCategory.EMAIL)
            String email) {}

    /** A renamed property, to prove the plan is matched by member and not by JSON name. */
    public record Contact(
            @PII(category = PiiCategory.EMAIL) @JsonProperty("email_address")
            String email) {}

    /** A getter-based bean, the shape a generated DTO or a Lombok class has. */
    public static final class LegacyCustomer {

        @PII(category = PiiCategory.EMAIL)
        private final String email;

        private final String country;

        public LegacyCustomer(String email, String country) {
            this.email = email;
            this.country = country;
        }

        public String getEmail() {
            return email;
        }

        public String getCountry() {
            return country;
        }
    }

    /**
     * A {@code @NoMask} value that a detector would otherwise rewrite. The exemption has to hold
     * against content scanning too, or it is not an exemption.
     */
    public record Reconciliation(
            @NoMask(justification = "the treasury's own account, published in the annual report")
            String houseIban) {}

    /** A masker that fails, to prove that a broken masker discloses nothing. */
    public static final class BrokenMasker implements Masker {

        @Override
        public Object mask(Object value, MaskContext context) {
            throw new IllegalStateException("this masker is broken");
        }
    }

    public record Fragile(@PII(masker = BrokenMasker.class) String secret) {}

    /**
     * A property that declares both what it is and how it should be rendered. Masking has to win,
     * or any custom serializer would be a way around the annotation.
     */
    public record Branded(
            @PII(category = PiiCategory.EMAIL) @JsonSerialize(using = ShoutingSerializer.class)
            String email) {}

    public static final class ShoutingSerializer extends ValueSerializer<String> {

        @Override
        public void serialize(String value, JsonGenerator generator, SerializationContext context) {
            generator.writeString(value.toUpperCase(Locale.ROOT));
        }
    }

    /** A tree nobody classified — a webhook body, a stored document, an audit detail. */
    public record Webhook(String id, JsonNode payload) {}

    /**
     * Free text whose author <em>declared</em> it as such. A detector hit here is the scanner doing
     * the job it was asked to do, not the discovery that a field nobody classified is leaking.
     */
    public record SupportTicket(
            @PII(category = PiiCategory.FREEFORM_TEXT) String body) {}

    /** A {@code CharSequence} that is not a {@code String}, which Jackson serialises differently. */
    public record Note(StringBuilder body) {}

    /**
     * The party of an order, written flattened into it. One property is masked, one is exempt from
     * masking and from the scanner, and one is dropped by a {@code PolicyOverrides} entry.
     */
    public record Party(
            @PII(category = PiiCategory.EMAIL) String email,

            @NoMask(justification = "the treasury's own account, published in the annual report")
            String houseIban,

            String reference) {}

    /** A holder that flattens the party into itself. */
    public static final class Order {

        private final String id;

        @JsonUnwrapped
        private final Party party;

        public Order(String id, Party party) {
            this.id = id;
            this.party = party;
        }

        public String getId() {
            return id;
        }

        public Party getParty() {
            return party;
        }
    }

    /** The same, under a prefix — which makes Jackson rebuild every property writer under a new name. */
    public static final class PrefixedOrder {

        @JsonUnwrapped(prefix = "party_")
        private final Party party;

        public PrefixedOrder(Party party) {
            this.party = party;
        }

        public Party getParty() {
            return party;
        }
    }

    /** A polymorphic hierarchy, the shape an API uses for a payment instrument. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = CardInstrument.class, name = "card"),
        @JsonSubTypes.Type(value = BankInstrument.class, name = "bank")
    })
    public sealed interface Instrument permits CardInstrument, BankInstrument {}

    public record CardInstrument(
            @PII(category = PiiCategory.PAN) String number) implements Instrument {}

    public record BankInstrument(
            @PII(category = PiiCategory.IBAN) String iban) implements Instrument {}

    public record Wallet(Instrument primary, List<Instrument> alternatives) {}

    /** A property that is classified <em>and</em> polymorphic: masking runs before the type serializer. */
    public record Envelope(
            @PII(category = PiiCategory.EMAIL)
            @JsonTypeInfo(use = JsonTypeInfo.Id.SIMPLE_NAME, include = JsonTypeInfo.As.WRAPPER_OBJECT)
            Object subject) {}
}
