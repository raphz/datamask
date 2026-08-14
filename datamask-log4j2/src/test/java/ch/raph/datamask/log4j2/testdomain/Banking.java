package ch.raph.datamask.log4j2.testdomain;

import ch.raph.datamask.api.MaskContext;
import ch.raph.datamask.api.MaskStrategy;
import ch.raph.datamask.api.Masker;
import ch.raph.datamask.api.NoMask;
import ch.raph.datamask.api.PII;
import ch.raph.datamask.api.PiiCategory;

/** The objects a payment service actually passes to a logger. */
public final class Banking {

    private Banking() {}

    /** A value object carrying its own classification, so every use of it is covered. */
    @PII(category = PiiCategory.EMAIL)
    public record Email(String value) {}

    public record Customer(
            @PII Email email,
            @PII(category = PiiCategory.IBAN) String iban,

            @NoMask(justification = "ISO country code identifies no one")
            String country) {}

    public record Card(
            @PII(category = PiiCategory.PAN) String number,

            @PII(category = PiiCategory.CARD_VERIFICATION_VALUE, keep = 3)
            String cvv) {}

    /** A masker that fails, to prove that a broken masker discloses nothing. */
    public static final class BrokenMasker implements Masker {

        @Override
        public Object mask(Object value, MaskContext context) {
            throw new IllegalStateException("this masker is broken");
        }
    }

    public record Fragile(@PII(masker = BrokenMasker.class) String secret) {}

    public record Reference(
            @PII(strategy = MaskStrategy.HASH) String correlationId) {}

    /** An exception with no {@code (String, Throwable)} constructor, to exercise the stand-in. */
    public static final class UnreconstructableException extends RuntimeException {

        public UnreconstructableException(Throwable cause, int unused) {
            super("Key (iban)=(CH9300762011623852957) already exists", cause);
        }
    }
}
