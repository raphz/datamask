package ch.raph.datamask.infrastructure.crypto;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.KDF;
import javax.crypto.spec.HKDFParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The secret that makes pseudonymisation keyed rather than merely hashed.
 *
 * <p>Without a key, a digest of an IBAN, a phone number or a Swiss AVS number is reversible by
 * enumeration in seconds — the input space is far too small — and would not qualify as
 * pseudonymisation under GDPR Article 4(5). The key is what turns the digest into a surrogate.
 *
 * <h2>Every key names itself</h2>
 *
 * {@link #id()} is a short, non-secret label derived from the key material through HKDF, and it is
 * written into every pseudonym. Two processes configured with the same secret compute the same id
 * with nothing to configure, and a pseudonym from before a rotation is recognisable as such instead
 * of being an unexplained mismatch — which is what makes rotating a key survivable at all.
 *
 * <h2>Zeroization</h2>
 *
 * The material is held once, here, and {@link #destroy()} wipes it. {@link #spec()} builds a
 * {@code SecretKeySpec} per call rather than retaining one, so after a destroy the only copies left
 * are unreachable garbage rather than a live field sitting in every heap dump.
 */
public final class MaskKey {

    /** Below this, a "secret" is a password someone typed, not key material. */
    private static final int MINIMUM_SECRET_BYTES = 16;

    private static final String ALGORITHM = "HmacSHA256";
    private static final String KDF_ALGORITHM = "HKDF-SHA256";
    private static final byte[] SALT = "ch.raph.datamask/mask-key".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] KEY_INFO = "pseudonymization/v1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ID_INFO = "key-id/v1".getBytes(StandardCharsets.US_ASCII);
    private static final int KEY_BYTES = 32;
    private static final int ID_BYTES = 4;

    private final byte[] material;
    private final String id;
    private final boolean ephemeral;
    private volatile boolean destroyed;

    private MaskKey(byte[] material, boolean ephemeral) {
        this.material = material;
        this.id = deriveId(material);
        this.ephemeral = ephemeral;
    }

    /**
     * A key derived from a configured secret, which must be at least 16 bytes.
     *
     * <p>The secret is run through HKDF-SHA-256 rather than used as the HMAC key directly: a
     * configured secret is usually a human-chosen passphrase, and an attacker holding one
     * (value, pseudonym) pair could otherwise brute-force the passphrase offline and then reverse
     * every pseudonym of a low-entropy input by enumeration. The derivation is deterministic, so
     * instances sharing a secret still produce joinable pseudonyms.
     */
    public static MaskKey ofSecret(String secret) {
        Objects.requireNonNull(secret, "secret");
        byte[] material = secret.getBytes(StandardCharsets.UTF_8);
        try {
            return fromSecretBytes(material);
        } finally {
            Arrays.fill(material, (byte) 0);
        }
    }

    /**
     * The same derivation from a {@code char[]}, for a secret read from a console or a file.
     *
     * <p>Worth preferring over the {@code String} overload: a {@code String} cannot be wiped, so a
     * secret that ever becomes one stays in the heap until the garbage collector happens to reuse
     * the memory — and shows up in every dump taken before then. The array is zeroed here; zero the
     * caller's copy too.
     */
    public static MaskKey ofSecret(char[] secret) {
        Objects.requireNonNull(secret, "secret");
        ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(secret));
        byte[] material = new byte[encoded.remaining()];
        encoded.get(material);
        Arrays.fill(encoded.array(), (byte) 0);
        try {
            return fromSecretBytes(material);
        } finally {
            Arrays.fill(material, (byte) 0);
        }
    }

    private static MaskKey fromSecretBytes(byte[] material) {
        if (material.length < MINIMUM_SECRET_BYTES) {
            throw new IllegalArgumentException("DataMask secret must be at least " + MINIMUM_SECRET_BYTES
                    + " bytes; a short secret makes pseudonyms recoverable by brute force");
        }
        return new MaskKey(expand(extract(material), KEY_INFO, KEY_BYTES), false);
    }

    /** Key material an application already holds — a value from a KMS or a vault, not a passphrase. */
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
        byte[] material = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(material);
        return new MaskKey(material, true);
    }

    /**
     * A separate key for a separate use, derived from this one.
     *
     * <p>Reusing one key for everything means a pseudonym issued for a support console and one
     * written into an export are the same string, so a leak of either joins both datasets. A
     * per-purpose subkey removes that link while keeping a single configured secret. The derivation
     * is deterministic, so the same purpose gives the same subkey everywhere.
     */
    public MaskKey forPurpose(String purpose) {
        Objects.requireNonNull(purpose, "purpose");
        byte[] info = ("purpose/" + purpose).getBytes(StandardCharsets.UTF_8);
        return new MaskKey(expand(material(), info, KEY_BYTES), ephemeral);
    }

    /**
     * The short public label for this key, written into every pseudonym it issues.
     *
     * <p>Derived from the material by a one-way function and truncated to four bytes, so it
     * identifies the key without being usable to recover anything about it.
     */
    public String id() {
        return id;
    }

    /** A fresh spec per call: nothing here retains one, so {@link #destroy()} has something to promise. */
    public SecretKeySpec spec() {
        return new SecretKeySpec(material(), ALGORITHM);
    }

    /** The MAC algorithm this key is for. Stated once, here, so nothing else has to name it. */
    public String algorithm() {
        return ALGORITHM;
    }

    public boolean isEphemeral() {
        return ephemeral;
    }

    /**
     * Wipes the key material. Any {@link #spec()} already handed out keeps its own copy, which is
     * beyond reach — take that as a reason to hold the {@code MaskKey} rather than the spec.
     */
    public void destroy() {
        destroyed = true;
        Arrays.fill(material, (byte) 0);
    }

    public boolean isDestroyed() {
        return destroyed;
    }

    private byte[] material() {
        if (destroyed) {
            throw new IllegalStateException("this MaskKey was destroyed; nothing can be pseudonymised with it");
        }
        return material;
    }

    private static String deriveId(byte[] material) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(expand(material, ID_INFO, ID_BYTES));
    }

    private static byte[] extract(byte[] secret) {
        return derive(HKDFParameterSpec.ofExtract().addIKM(secret).addSalt(SALT).extractOnly());
    }

    private static byte[] expand(byte[] pseudoRandomKey, byte[] info, int length) {
        return derive(HKDFParameterSpec.expandOnly(new SecretKeySpec(pseudoRandomKey, ALGORITHM), info, length));
    }

    private static byte[] derive(java.security.spec.AlgorithmParameterSpec derivation) {
        try {
            return KDF.getInstance(KDF_ALGORITHM).deriveData(derivation);
        } catch (NoSuchAlgorithmException | InvalidAlgorithmParameterException e) {
            throw new IllegalStateException(KDF_ALGORITHM + " is unavailable in this JVM", e);
        }
    }

    @Override
    public String toString() {
        return "MaskKey[" + (ephemeral ? "ephemeral" : "configured") + " " + id + ", material hidden]";
    }
}
