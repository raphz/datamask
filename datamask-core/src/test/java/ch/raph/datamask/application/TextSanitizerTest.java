package ch.raph.datamask.application;

import static org.assertj.core.api.Assertions.assertThat;

import ch.raph.datamask.api.PiiCategory;
import ch.raph.datamask.domain.PiiFinding;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Free-text scanning")
class TextSanitizerTest {

    private final DataMask dataMask =
            DataMask.builder().secret("a-test-secret-of-sufficient-length").build();

    @Test
    @DisplayName("masks an email inside a sentence and leaves the sentence readable")
    void masksEmailInProse() {
        assertThat(dataMask.maskText("Please contact john.doe@example.com before Friday."))
                .isEqualTo("Please contact j*******@e******.com before Friday.");
    }

    @Test
    @DisplayName("masks a card number confirmed by Luhn")
    void masksCardNumber() {
        assertThat(dataMask.maskText("charged card 4111111111111111 today"))
                .isEqualTo("charged card **** **** **** 1111 today");
    }

    @Test
    @DisplayName("leaves a long reference number alone, because it fails Luhn")
    void ignoresNonCardDigits() {
        assertThat(dataMask.maskText("order 1234567890123456 shipped")).isEqualTo("order 1234567890123456 shipped");
    }

    @Test
    @DisplayName("masks an IBAN confirmed by mod-97")
    void masksIban() {
        assertThat(dataMask.maskText("transfer from CH9300762011623852957 received"))
                .isEqualTo("transfer from CH93 **** **** **** *295 7 received");
    }

    @Test
    @DisplayName("masks a Swiss AVS number")
    void masksSwissAhv() {
        assertThat(dataMask.maskText("AVS 756.9217.0769.85 on file")).isEqualTo("AVS ***.****.****.** on file");
    }

    @Test
    @DisplayName("masks the token but keeps the header name that gives it context")
    void masksBearerToken() {
        assertThat(dataMask.maskText("Authorization: Bearer abcdef0123456789")).isEqualTo("Authorization: Bearer ****");
    }

    @Test
    @DisplayName("masks a value assigned to something that sounds like a secret")
    void masksAssignedSecrets() {
        assertThat(dataMask.maskText("password=hunter2000 loaded")).isEqualTo("password=**** loaded");
    }

    @Test
    @DisplayName("masks a JSON Web Token")
    void masksJwt() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0In0.abc123";

        assertThat(dataMask.maskText("token " + jwt)).isEqualTo("token ****");
    }

    @Test
    @DisplayName("masks several values in one string")
    void masksMultipleFindings() {
        String masked = dataMask.maskText("john.doe@example.com paid with 4111111111111111 from CH9300762011623852957");

        assertThat(masked)
                .doesNotContain("john.doe@example.com")
                .doesNotContain("4111111111111111")
                .doesNotContain("CH9300762011623852957");
    }

    @Test
    @DisplayName("returns text without PII unchanged")
    void leavesCleanTextAlone() {
        assertThat(dataMask.maskText("payment accepted")).isEqualTo("payment accepted");
    }

    @Test
    @DisplayName("reports findings without changing the text, for auditing a payload")
    void scansWithoutMasking() {
        List<PiiFinding> findings = dataMask.scan("mail john.doe@example.com card 4111111111111111");

        assertThat(findings)
                .extracting(PiiFinding::category)
                .containsExactlyInAnyOrder(PiiCategory.EMAIL, PiiCategory.PAN);
        assertThat(findings).allMatch(PiiFinding::confident);
    }
}
