package ch.raph.datamask.infrastructure.crypto;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import javax.crypto.spec.SecretKeySpec;

/**
 * The secret that makes pseudonymisation keyed rather than merely hashed.
 *
 * <p>Without a key, a digest of an IBAN, a phone number or a Swiss AVS number is reversible by
 * enumeration in seconds — the input space is far too small — and would not qualify as
 * pseudonymisation under GDPR Article 4(5). The key is what turns the digest into a surrogate.
 */
public final class MaskKey {

    /** Below this, a "secret" is a password someone typed, not key material. */
    private static final int MINIMUM_SECRET_BYTES = 16;

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec spec;
    private final boolean ephemeral;

    private MaskKey(byte[] material, boolean ephemeral) {
        this.spec = new SecretKeySpec(material, ALGORITHM);
        this.ephemeral = ephemeral;
    }

    /** A key derived from a configured secret, which must be at least 16 bytes. */
    public static MaskKey ofSecret(String secret) {
        Objects.requireNonNull(secret, "secret");
        byte[] material = secret.getBytes(StandardCharsets.UTF_8);
        if (material.length < MINIMUM_SECRET_BYTES) {
            throw new IllegalArgumentException("DataMask secret must be at least " + MINIMUM_SECRET_BYTES
                    + " bytes; a short secret makes pseudonyms recoverable by brute force");
        }
        return new MaskKey(material, false);
    }

    public static MaskKey of(byte[] material) {
        Objects.requireNonNull(material, "material");
        if (material.length < MINIMUM_SECRET_BYTES) {
            throw new IllegalArgumentException("DataMask key must be at least " + MINIMUM_SECRET_BYTES + " bytes");
        }
        return new MaskKey(Arrays.copyOf(material, material.length), false);
    }

    /**
     * A random key that lives and dies with this JVM.
     *
     * <p>Safe, but pseudonyms stop being comparable across instances and across restarts, which
     * removes the main reason to prefer hashing over redaction. Intended for tests and local
     * development only — the Spring auto-configuration refuses to fall back to it silently.
     */
    public static MaskKey ephemeral() {
        byte[] material = new byte[32];
        new SecureRandom().nextBytes(material);
        return new MaskKey(material, true);
    }

    public SecretKeySpec spec() {
        return spec;
    }

    public boolean isEphemeral() {
        return ephemeral;
    }

    @Override
    public String toString() {
        return "MaskKey[" + (ephemeral ? "ephemeral" : "configured") + ", material hidden]";
    }
}
