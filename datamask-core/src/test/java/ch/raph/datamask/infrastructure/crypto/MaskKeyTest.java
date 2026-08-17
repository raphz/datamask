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

    @Test
    @DisplayName("derives the same key from a char[] as from the equivalent String")
    void charArrayDerivesTheSameKey() {
        assertThat(MaskKey.ofSecret(SECRET.toCharArray()).spec().getEncoded())
                .isEqualTo(MaskKey.ofSecret(SECRET).spec().getEncoded());
    }

    @Test
    @DisplayName("gives the same secret the same key id everywhere, with nothing to configure")
    void keyIdIsDerivedFromTheMaterial() {
        assertThat(MaskKey.ofSecret(SECRET).id())
                .isEqualTo(MaskKey.ofSecret(SECRET).id());
        assertThat(MaskKey.ofSecret(SECRET).id())
                .isNotEqualTo(MaskKey.ofSecret(SECRET + "-rotated").id());
    }

    @Test
    @DisplayName("the key id discloses nothing about the secret")
    void keyIdIsNotTheSecret() {
        MaskKey key = MaskKey.ofSecret(SECRET);

        assertThat(key.id())
                .hasSize(6)
                .doesNotContain(SECRET)
                .isNotEqualTo(java.util.Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(key.spec().getEncoded()));
    }

    @Test
    @DisplayName("derives a different key per purpose, so one leak does not join two datasets")
    void purposeKeysDiverge() {
        MaskKey key = MaskKey.ofSecret(SECRET);

        assertThat(key.forPurpose("export").spec().getEncoded())
                .isNotEqualTo(key.forPurpose("support").spec().getEncoded())
                .isNotEqualTo(key.spec().getEncoded());
        assertThat(key.forPurpose("export").id())
                .isEqualTo(MaskKey.ofSecret(SECRET).forPurpose("export").id());
    }

    @Test
    @DisplayName("wipes its material on destroy and refuses to be used afterwards")
    void destroyZeroesTheMaterial() {
        MaskKey key = MaskKey.ofSecret(SECRET);
        key.destroy();

        assertThat(key.isDestroyed()).isTrue();
        assertThatThrownBy(key::spec).isInstanceOf(IllegalStateException.class).hasMessageContaining("destroyed");
    }
}
