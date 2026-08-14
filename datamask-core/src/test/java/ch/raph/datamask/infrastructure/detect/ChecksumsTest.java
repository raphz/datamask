package ch.raph.datamask.infrastructure.detect;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Check digits")
class ChecksumsTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "CH9300762011623852957", // Swiss
                "DE89370400440532013000", // German
                "GB82WEST12345698765432", // British
                "FR1420041010050500013M02606"
            })
    @DisplayName("accepts IBANs whose mod-97 remainder is one")
    void acceptsValidIbans(String iban) {
        assertThat(Checksums.iban(iban)).isTrue();
    }

    @Test
    @DisplayName("accepts an IBAN written with the usual spacing")
    void acceptsSpacedIban() {
        assertThat(Checksums.iban("CH93 0076 2011 6238 5295 7")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "CH9300762011623852958", // last digit altered
                "XX9300762011623852957", // no such country
                "CH93", // too short
                "9300762011623852957"
            }) // no country prefix
    @DisplayName("rejects malformed or altered IBANs")
    void rejectsInvalidIbans(String candidate) {
        assertThat(Checksums.iban(candidate)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"4111111111111111", "5500005555555559", "378282246310005"})
    @DisplayName("accepts card numbers that satisfy Luhn")
    void acceptsValidCards(String pan) {
        assertThat(Checksums.luhn(pan)).isTrue();
    }

    @Test
    @DisplayName("rejects a run of digits that only looks like a card number")
    void rejectsNonCardDigits() {
        assertThat(Checksums.luhn("1234567890123456")).isFalse();
        assertThat(Checksums.luhn("4111111111111112")).isFalse();
    }

    @Test
    @DisplayName("validates a Swiss AVS number by its EAN-13 check digit")
    void validatesSwissAhv() {
        assertThat(Checksums.swissAhv("756.9217.0769.85")).isTrue();
        assertThat(Checksums.swissAhv("7569217076985")).isTrue();
        assertThat(Checksums.swissAhv("756.9217.0769.86")).isFalse();
        assertThat(Checksums.swissAhv("123.9217.0769.85")).isFalse();
    }
}
