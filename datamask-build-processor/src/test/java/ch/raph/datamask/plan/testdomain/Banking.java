package ch.raph.datamask.plan.testdomain;

import ch.raph.datamask.api.MaskContext;
import ch.raph.datamask.api.MaskStrategy;
import ch.raph.datamask.api.Masker;
import ch.raph.datamask.api.NoMask;
import ch.raph.datamask.api.PII;
import ch.raph.datamask.api.PiiCategory;
import ch.raph.datamask.api.Sensitivity;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The shared banking domain, plus the shapes that only a generated plan has an opinion about.
 *
 * <p>Everything here is compiled twice: once by Gradle, so the test can hold instances of it, and
 * once by {@code Generation} with the processor attached, so the plans it emits can be compared
 * against what the reflective compiler makes of the very same classes. That is why this file is
 * read from the source tree rather than written inline — the two compilers have to be looking at
 * the same source, or the comparison proves nothing.
 */
public final class Banking {

    private Banking() {}

    /** A value object that carries its own classification, so every use of it is covered. */
    @PII(category = PiiCategory.EMAIL)
    public record Email(String value) {}

    /** The example from the README, verbatim. */
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
            @PII(category = PiiCategory.FINANCIAL_AMOUNT) BigDecimal balance,

            @NoMask(justification = "ISO currency code identifies no one")
            String currency) {}

    public record Profile(
            @PII(category = PiiCategory.FULL_NAME) String name,
            @PII(category = PiiCategory.DATE_OF_BIRTH) LocalDate birthDate,
            @PII(category = PiiCategory.NATIONAL_ID) String avs,
            @PII(category = PiiCategory.PHONE) String phone,
            @PII(category = PiiCategory.IP_ADDRESS) String lastLoginIp,
            @PII(category = PiiCategory.CUSTOMER_ID) String customerId,
            @PII(category = PiiCategory.FREEFORM_TEXT) String note,
            @PII(category = PiiCategory.CREDENTIAL) String apiKey,
            String segment) {}

    /** Nesting, collections and maps, to exercise traversal — and to be reached by containment. */
    public record Portfolio(String reference, Customer owner, List<Account> accounts, Map<String, Card> cardsByAlias) {}

    public record LowRisk(
            @PII(sensitivity = Sensitivity.LOW, category = PiiCategory.EMAIL)
            String email) {}

    /**
     * Every member shape the two compilers classify without an annotation to go on: a primitive, an
     * enum, a JDK value type, a container, an array, an interface and {@code Object} itself. This is
     * where {@code LeafTypes} and the runtime's {@code Types} would disagree if they ever drifted.
     */
    public record Movement(
            @PII(category = PiiCategory.ACCOUNT_NUMBER) String account,
            int sequence,
            long timestamp,
            char direction,
            boolean reversed,
            Channel channel,
            BigDecimal amount,
            LocalDate valueDate,
            UUID correlationId,
            String reference,
            CharSequence memo,
            String[] tags,
            List<String> labels,
            Optional<String> nickname,
            Object payload,
            Runnable onSettled) {}

    public enum Channel {
        BRANCH,
        MOBILE
    }

    /** Attributes that have to survive being written back out as source, quotes and all. */
    public record Awkward(
            @PII(
                    strategy = MaskStrategy.PARTIAL,
                    category = PiiCategory.ACCOUNT_NUMBER,
                    keep = 2,
                    padding = '#',
                    replacement = "n/a \"unknown\"\\",
                    purpose = "audit\ttrail\nrecord")
            String reference,

            @PII(masker = Shout.class) String shouted) {}

    /** A custom masker named from an annotation, so the generated descriptor has a class to write. */
    public static final class Shout implements Masker {

        @Override
        public Object mask(Object value, MaskContext context) {
            return "HIDDEN";
        }
    }

    /** A mutable bean with an all-arguments constructor, the shape Lombok generates. */
    public static final class LegacyCustomer {

        @PII(category = PiiCategory.EMAIL)
        private String email;

        private String country;

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

    /** A bean with a no-argument constructor and real setters: the second rebuild shape. */
    public static final class SettableCustomer {

        @PII(category = PiiCategory.EMAIL)
        private String email;

        private String country;

        public SettableCustomer() {}

        public static SettableCustomer of(String email, String country) {
            SettableCustomer customer = new SettableCustomer();
            customer.setEmail(email);
            customer.setCountry(country);
            return customer;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public String getCountry() {
            return country;
        }

        public void setCountry(String country) {
            this.country = country;
        }
    }

    /** Package-private fields, which a generated plan in the same package reads and writes directly. */
    public static final class OpenCustomer {

        @PII(category = PiiCategory.EMAIL)
        String email;

        String country;

        public OpenCustomer() {}

        public static OpenCustomer of(String email, String country) {
            OpenCustomer customer = new OpenCustomer();
            customer.email = email;
            customer.country = country;
            return customer;
        }

        public String getEmail() {
            return email;
        }

        public String getCountry() {
            return country;
        }
    }

    /**
     * Private fields, no setters and only a no-argument constructor: the reflective compiler writes
     * them through a private lookup and a generated plan cannot. This is the type that proves the
     * fallback is load-bearing rather than theoretical.
     */
    public static final class MutableCustomer {

        @PII(category = PiiCategory.EMAIL)
        private String email;

        private String country;

        public MutableCustomer() {}

        public static MutableCustomer of(String email, String country) {
            MutableCustomer customer = new MutableCustomer();
            customer.email = email;
            customer.country = country;
            return customer;
        }

        public String getEmail() {
            return email;
        }

        public String getCountry() {
            return country;
        }
    }

    /** Self-referencing, to prove the walk terminates. Falls back for the same reason as the above. */
    public static final class Node {

        @PII(category = PiiCategory.EMAIL)
        private String email;

        private Node next;

        public Node() {}

        public static Node of(String email) {
            Node node = new Node();
            node.email = email;
            return node;
        }

        public Node linkTo(Node other) {
            this.next = other;
            return this;
        }

        public String getEmail() {
            return email;
        }

        public Node getNext() {
            return next;
        }
    }
}
