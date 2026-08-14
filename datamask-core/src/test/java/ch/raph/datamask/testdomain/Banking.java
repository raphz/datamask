package ch.raph.datamask.testdomain;

import ch.raph.datamask.api.MaskStrategy;
import ch.raph.datamask.api.NoMask;
import ch.raph.datamask.api.PII;
import ch.raph.datamask.api.PiiCategory;
import ch.raph.datamask.api.Sensitivity;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** A small domain shaped like the ones this library is meant to protect. */
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

    /** Nesting, collections and maps, to exercise traversal. */
    public record Portfolio(String reference, Customer owner, List<Account> accounts, Map<String, Card> cardsByAlias) {}

    public record LowRisk(
            @PII(sensitivity = Sensitivity.LOW, category = PiiCategory.EMAIL)
            String email) {}

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

    /** A bean with only a no-argument constructor and settable fields. */
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

    /** Self-referencing, to prove the walk terminates. */
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
