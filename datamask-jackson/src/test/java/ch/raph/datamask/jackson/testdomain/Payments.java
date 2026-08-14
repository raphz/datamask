package ch.raph.datamask.jackson.testdomain;

import ch.raph.datamask.api.MaskContext;
import ch.raph.datamask.api.MaskStrategy;
import ch.raph.datamask.api.Masker;
import ch.raph.datamask.api.NoMask;
import ch.raph.datamask.api.PII;
import ch.raph.datamask.api.PiiCategory;
import ch.raph.datamask.api.Sensitivity;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.core.JsonGenerator;
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
}
