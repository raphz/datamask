package ch.raph.datamask.kafka.testdomain;

import ch.raph.datamask.api.MaskContext;
import ch.raph.datamask.api.MaskStrategy;
import ch.raph.datamask.api.Masker;
import ch.raph.datamask.api.NoMask;
import ch.raph.datamask.api.PII;
import ch.raph.datamask.api.PiiCategory;

/** The payloads a payment service actually publishes. */
public final class Payments {

    private Payments() {}

    /** A value object carrying its own classification, so every use of it is covered. */
    @PII(category = PiiCategory.EMAIL)
    public record Email(String value) {}

    public record Payment(
            @PII Email email,
            @PII(category = PiiCategory.IBAN) String iban,
            @PII(category = PiiCategory.PAN) String card,

            @NoMask(justification = "ISO currency code identifies no one")
            String currency,

            @NoMask(justification = "an amount without a party to attach it to identifies no one")
            long cents) {}

    public record Reference(
            @PII(strategy = MaskStrategy.HASH) String customerId) {}

    /** A masker that fails, to prove a broken masker discloses nothing. */
    public static final class BrokenMasker implements Masker {

        @Override
        public Object mask(Object value, MaskContext context) {
            throw new IllegalStateException("this masker is broken");
        }
    }

    public record Fragile(@PII(masker = BrokenMasker.class) String secret) {}

    /**
     * A payload the engine cannot rebuild: no canonical constructor, no all-arguments constructor and
     * no no-argument constructor. This is what a masking failure that reaches the boundary looks like.
     */
    public static final class Unrebuildable {

        @PII(category = PiiCategory.IBAN)
        private final String iban;

        public Unrebuildable(String iban, String ignored, int alsoIgnored) {
            this.iban = iban;
        }

        public String iban() {
            return iban;
        }
    }
}
