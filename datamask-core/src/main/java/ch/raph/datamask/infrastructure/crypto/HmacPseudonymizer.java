package ch.raph.datamask.infrastructure.crypto;

import ch.raph.datamask.domain.MaskingException;
import ch.raph.datamask.domain.Pseudonymizer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import javax.crypto.Mac;

/**
 * HMAC-SHA-256 pseudonymisation, truncated to a length that stays readable in a log line.
 *
 * <p>Ninety-six bits of output is far more than is needed to avoid collisions across any realistic
 * customer base, and short enough that an operator can compare two pseudonyms by eye.
 *
 * <h2>Pseudonyms name the key that made them</h2>
 *
 * A pseudonym reads {@code ~<keyId>:<digest>}. Without the key id, rotating the secret turned every
 * pseudonym issued before the rotation into a value that silently no longer joined to anything —
 * the same customer appearing as two, with no error and nothing in a log to explain it. With it, a
 * pseudonym from the previous key is recognisable, and {@link #matches(String, String)} can still
 * confirm it against a keyring covering the rotation window.
 *
 * <p>Rotating therefore means: add the new key as current, keep the old one as previous until the
 * data pseudonymised under it has aged out, then drop it.
 */
public final class HmacPseudonymizer implements Pseudonymizer {

    private static final int DEFAULT_OUTPUT_BYTES = 12;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final MaskKey key;
    private final List<MaskKey> previousKeys;
    private final int outputBytes;
    private final String prefix;

    public HmacPseudonymizer(MaskKey key) {
        this(key, List.of(), DEFAULT_OUTPUT_BYTES, "~");
    }

    /**
     * @param previousKeys keys this deployment has rotated away from, still recognised by
     *                     {@link #matches(String, String)} so a rotation does not orphan the
     *                     pseudonyms already written
     */
    public HmacPseudonymizer(MaskKey key, List<MaskKey> previousKeys) {
        this(key, previousKeys, DEFAULT_OUTPUT_BYTES, "~");
    }

    /**
     * @param prefix marks the value as a surrogate, so nobody mistakes a pseudonym for a real
     *               identifier when reading a log or pasting one into a query
     */
    public HmacPseudonymizer(MaskKey key, List<MaskKey> previousKeys, int outputBytes, String prefix) {
        this.key = Objects.requireNonNull(key, "key");
        this.previousKeys = List.copyOf(Objects.requireNonNull(previousKeys, "previousKeys"));
        this.prefix = Objects.requireNonNull(prefix, "prefix");
        if (outputBytes < 8 || outputBytes > 32) {
            throw new IllegalArgumentException("outputBytes must be between 8 and 32, was " + outputBytes);
        }
        this.outputBytes = outputBytes;
    }

    @Override
    public String pseudonymize(String value) {
        return value == null ? null : pseudonymizeWith(key, value);
    }

    /**
     * Whether a pseudonym — this key's or one from the keyring — stands for this value.
     *
     * <p>The point of a keyed surrogate is that it can be re-derived but not reversed, so joining
     * an old record to a known value means recomputing, not decoding. The key id in the pseudonym
     * says which key to recompute under, so this costs one HMAC rather than one per key held.
     */
    public boolean matches(String value, String pseudonym) {
        if (value == null || pseudonym == null) {
            return false;
        }
        return keyFor(pseudonym)
                .map(k -> constantTimeEquals(pseudonymizeWith(k, value), pseudonym))
                .orElse(false);
    }

    /** The id of the key currently issuing pseudonyms, as it appears in each one. */
    public String keyId() {
        return key.id();
    }

    public boolean usesEphemeralKey() {
        return key.isEphemeral();
    }

    private java.util.Optional<MaskKey> keyFor(String pseudonym) {
        int separator = pseudonym.indexOf(':');
        if (!pseudonym.startsWith(prefix) || separator < 0) {
            return java.util.Optional.empty();
        }
        String id = pseudonym.substring(prefix.length(), separator);
        if (key.id().equals(id)) {
            return java.util.Optional.of(key);
        }
        return previousKeys.stream()
                .filter(candidate -> candidate.id().equals(id))
                .findFirst();
    }

    private String pseudonymizeWith(MaskKey withKey, String value) {
        try {
            // A Mac is not thread-safe and cannot be cached in a field. Caching it per thread would
            // pin memory per virtual thread, so it is created per call; at roughly a microsecond
            // this is negligible next to the cost of the log or span it feeds.
            Mac mac = Mac.getInstance(withKey.algorithm());
            mac.init(withKey.spec());
            byte[] digest = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] truncated = new byte[outputBytes];
            System.arraycopy(digest, 0, truncated, 0, outputBytes);
            return prefix + withKey.id() + ":" + ENCODER.encodeToString(truncated);
        } catch (GeneralSecurityException e) {
            throw MaskingException.withoutPath("HMAC-SHA-256 is unavailable in this JVM");
        }
    }

    /** Comparing surrogates is a guess-checking oracle otherwise, one character at a time. */
    private static boolean constantTimeEquals(String left, String right) {
        return java.security.MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }
}
