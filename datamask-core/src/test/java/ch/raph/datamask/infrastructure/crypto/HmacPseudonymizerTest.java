package ch.raph.datamask.infrastructure.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Keyed pseudonymisation")
class HmacPseudonymizerTest {

    private static final String SECRET = "a-test-secret-of-sufficient-length";
    private static final String ROTATED = "the-secret-this-deployment-moved-to";
    private static final String IBAN = "CH9300762011623852957";

    @Test
    @DisplayName("writes the id of the issuing key into every pseudonym")
    void pseudonymNamesItsKey() {
        MaskKey key = MaskKey.ofSecret(SECRET);

        String pseudonym = new HmacPseudonymizer(key).pseudonymize(IBAN);

        assertThat(pseudonym).startsWith("~" + key.id() + ":").doesNotContain(IBAN);
    }

    @Test
    @DisplayName("stays deterministic across instances, so pseudonyms join across services")
    void isDeterministic() {
        assertThat(new HmacPseudonymizer(MaskKey.ofSecret(SECRET)).pseudonymize(IBAN))
                .isEqualTo(new HmacPseudonymizer(MaskKey.ofSecret(SECRET)).pseudonymize(IBAN));
    }

    @Test
    @DisplayName("still recognises a pseudonym issued under a key it has rotated away from")
    void matchesAcrossARotation() {
        MaskKey previous = MaskKey.ofSecret(SECRET);
        String issuedBefore = new HmacPseudonymizer(previous).pseudonymize(IBAN);

        HmacPseudonymizer afterRotation = new HmacPseudonymizer(MaskKey.ofSecret(ROTATED), List.of(previous));

        // Without the key id there is nothing in the old pseudonym to say which key made it, so a
        // rotation silently turns every record written before it into an unjoinable stranger.
        assertThat(afterRotation.matches(IBAN, issuedBefore)).isTrue();
        assertThat(afterRotation.pseudonymize(IBAN)).isNotEqualTo(issuedBefore);
        assertThat(afterRotation.matches(IBAN, afterRotation.pseudonymize(IBAN)))
                .isTrue();
    }

    @Test
    @DisplayName("does not recognise a pseudonym from a key it never held")
    void refusesAnUnknownKey() {
        String foreign = new HmacPseudonymizer(MaskKey.ofSecret(ROTATED)).pseudonymize(IBAN);

        assertThat(new HmacPseudonymizer(MaskKey.ofSecret(SECRET)).matches(IBAN, foreign))
                .isFalse();
    }

    @Test
    @DisplayName("does not confirm a pseudonym for the wrong value")
    void refusesTheWrongValue() {
        HmacPseudonymizer pseudonymizer = new HmacPseudonymizer(MaskKey.ofSecret(SECRET));

        assertThat(pseudonymizer.matches("CH5604835012345678009", pseudonymizer.pseudonymize(IBAN)))
                .isFalse();
    }
}
