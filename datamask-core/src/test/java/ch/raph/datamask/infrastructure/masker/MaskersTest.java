package ch.raph.datamask.infrastructure.masker;

import static org.assertj.core.api.Assertions.assertThat;

import ch.raph.datamask.api.MaskContext;
import ch.raph.datamask.api.MaskStrategy;
import ch.raph.datamask.api.PiiCategory;
import ch.raph.datamask.api.Sensitivity;
import ch.raph.datamask.infrastructure.crypto.HmacPseudonymizer;
import ch.raph.datamask.infrastructure.crypto.MaskKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Built-in maskers")
class MaskersTest {

    private static final HmacPseudonymizer PSEUDONYMIZER =
            new HmacPseudonymizer(MaskKey.ofSecret("a-test-secret-of-sufficient-length"));

    @Test
    @DisplayName("IBAN masking reveals scheme and suffix of a value shaped like an IBAN")
    void masksRealIban() {
        Object masked = new IbanMasker().mask("CH9300762011623852957", context(PiiCategory.IBAN, MaskStrategy.IBAN));

        assertThat(masked).hasToString("CH93 **** **** **** *295 7");
    }

    @Test
    @DisplayName("IBAN masking refuses to reveal anything of a value that is not shaped like an IBAN")
    void refusesNonIban() {
        IbanMasker masker = new IbanMasker();
        MaskContext context = context(PiiCategory.IBAN, MaskStrategy.IBAN);

        assertThat(masker.mask("PASSWORD123", context)).hasToString("****");
        assertThat(masker.mask("1234567890123456", context)).hasToString("****");
        assertThat(masker.mask("CH93", context)).hasToString("****");
    }

    @Test
    @DisplayName("format-preserving masking substitutes letters of every script, not only ASCII")
    void formatPreservingMasksAllScripts() {
        Object masked = new FormatPreservingMasker()
                .mask("Müller Иванов", context(PiiCategory.FULL_NAME, MaskStrategy.PRESERVE_FORMAT));

        assertThat(masked.toString())
                .doesNotContain("üller")
                .doesNotContain("Иванов")
                .matches("[A-Z][a-z]+ [A-Z][a-z]+")
                .hasSize("Müller Иванов".length());
    }

    @Test
    @DisplayName("format-preserving masking keeps digit layout and stays deterministic under one key")
    void formatPreservingIsDeterministic() {
        FormatPreservingMasker masker = new FormatPreservingMasker();
        MaskContext context = context(PiiCategory.PHONE, MaskStrategy.PRESERVE_FORMAT);

        Object first = masker.mask("020 7946 0958", context);
        Object second = masker.mask("020 7946 0958", context);

        assertThat(first.toString()).matches("\\d{3} \\d{4} \\d{4}").isNotEqualTo("020 7946 0958");
        assertThat(second).hasToString(first.toString());
    }

    @Test
    @DisplayName("every revealing masker yields the placeholder for a never-partially-revealed category")
    void revealingMaskersRefuseNeverRevealCategories() {
        MaskContext cvv = context(PiiCategory.CARD_VERIFICATION_VALUE, MaskStrategy.PARTIAL);

        assertThat(new PartialMasker().mask("1234", cvv)).hasToString("****");
        assertThat(new PanMasker().mask("4111111111111111", cvv)).hasToString("****");
        assertThat(new IbanMasker().mask("CH9300762011623852957", cvv)).hasToString("****");
        assertThat(new EmailMasker().mask("a@b.com", cvv)).hasToString("****");
        assertThat(new PhoneMasker().mask("+41791234567", cvv)).hasToString("****");
        assertThat(new NameMasker().mask("John Doe", cvv)).hasToString("****");
        assertThat(new IpAddressMasker().mask("192.168.4.37", cvv)).hasToString("****");
        assertThat(new DateGeneralizeMasker().mask("2027-11-01", cvv)).hasToString("****");
        assertThat(new FormatPreservingMasker().mask("123", cvv)).hasToString("****");
    }

    @Test
    @DisplayName("generalises the legacy java.sql date and time types instead of throwing on them")
    void generalisesSqlTemporalTypes() {
        MaskContext birthDate = context(PiiCategory.DATE_OF_BIRTH, MaskStrategy.DATE_GENERALIZE);
        DateGeneralizeMasker masker = new DateGeneralizeMasker();

        // java.sql.Date and java.sql.Time throw from toInstant() by contract, and java.sql.Date is
        // the single most common type a legacy schema gives a birth date — so the branch that
        // reached for toInstant() failed on exactly the value this masker exists for.
        assertThat(masker.mask(java.sql.Date.valueOf(java.time.LocalDate.of(1985, 7, 14)), birthDate))
                .isInstanceOf(java.sql.Date.class)
                .hasToString("1985-01-01");
        assertThat(masker.mask(java.sql.Time.valueOf(java.time.LocalTime.of(14, 32, 8)), birthDate))
                .isInstanceOf(java.sql.Time.class);
        assertThat(masker.mask(java.sql.Timestamp.valueOf(java.time.LocalDateTime.of(1985, 7, 14, 9, 30)), birthDate))
                .isInstanceOf(java.sql.Timestamp.class)
                .asString()
                .startsWith("1985-01-01");
    }

    private static MaskContext context(PiiCategory category, MaskStrategy strategy) {
        return new MaskContext() {
            @Override
            public PiiCategory category() {
                return category;
            }

            @Override
            public Sensitivity sensitivity() {
                return Sensitivity.HIGH;
            }

            @Override
            public MaskStrategy strategy() {
                return strategy;
            }

            @Override
            public int keep() {
                return category.neverPartiallyReveal() ? 0 : category.defaultKeep();
            }

            @Override
            public char padding() {
                return '*';
            }

            @Override
            public String replacement() {
                return "";
            }

            @Override
            public String path() {
                return "test";
            }

            @Override
            public Class<?> declaredType() {
                return String.class;
            }

            @Override
            public String redactionPlaceholder() {
                return "****";
            }

            @Override
            public String pseudonymize(String value) {
                return PSEUDONYMIZER.pseudonymize(value);
            }

            @Override
            public String tokenize(String value) {
                throw new UnsupportedOperationException("no vault in this test");
            }
        };
    }
}
