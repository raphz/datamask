package ch.raph.datamask.infrastructure.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Mask key")
class MaskKeyTest {

    private static final String SECRET = "a-test-secret-of-sufficient-length";

    @Test
    @DisplayName("derives the same key from the same secret, so pseudonyms stay joinable")
    void derivationIsDeterministic() {
        assertThat(MaskKey.ofSecret(SECRET).spec().getEncoded())
                .isEqualTo(MaskKey.ofSecret(SECRET).spec().getEncoded());
    }

    @Test
    @DisplayName("never uses the raw passphrase bytes as the HMAC key")
    void passphraseIsRunThroughHkdf() {
        byte[] derived = MaskKey.ofSecret(SECRET).spec().getEncoded();

        assertThat(derived).hasSize(32).isNotEqualTo(SECRET.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("uses explicit key material as-is, for callers bringing real keys")
    void explicitMaterialIsNotDerived() {
        byte[] material = new byte[32];
        material[0] = 42;

        assertThat(MaskKey.of(material).spec().getEncoded()).isEqualTo(material);
    }

    @Test
    @DisplayName("refuses a secret short enough to brute-force")
    void rejectsShortSecrets() {
        assertThatThrownBy(() -> MaskKey.ofSecret("changeme"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 16 bytes");
    }

    @Test
    @DisplayName("never prints key material")
    void toStringHidesMaterial() {
        assertThat(MaskKey.ofSecret(SECRET).toString()).doesNotContain(SECRET).contains("hidden");
    }
}
