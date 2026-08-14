package ch.raph.datamask.infrastructure.crypto;

import ch.raph.datamask.domain.MaskingException;
import ch.raph.datamask.domain.Pseudonymizer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Mac;

/**
 * HMAC-SHA-256 pseudonymisation, truncated to a length that stays readable in a log line.
 *
 * <p>Ninety-six bits of output is far more than is needed to avoid collisions across any realistic
 * customer base, and short enough that an operator can compare two pseudonyms by eye.
 */
public final class HmacPseudonymizer implements Pseudonymizer {

    private static final String ALGORITHM = "HmacSHA256";
    private static final int DEFAULT_OUTPUT_BYTES = 12;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final MaskKey key;
    private final int outputBytes;
    private final String prefix;

    public HmacPseudonymizer(MaskKey key) {
        this(key, DEFAULT_OUTPUT_BYTES, "~");
    }

    /**
     * @param prefix marks the value as a surrogate, so nobody mistakes a pseudonym for a real
     *               identifier when reading a log or pasting one into a query
     */
    public HmacPseudonymizer(MaskKey key, int outputBytes, String prefix) {
        this.key = Objects.requireNonNull(key, "key");
        this.prefix = Objects.requireNonNull(prefix, "prefix");
        if (outputBytes < 8 || outputBytes > 32) {
            throw new IllegalArgumentException("outputBytes must be between 8 and 32, was " + outputBytes);
        }
        this.outputBytes = outputBytes;
    }

    @Override
    public String pseudonymize(String value) {
        if (value == null) {
            return null;
        }
        try {
            // A Mac is not thread-safe and cannot be cached in a field. Caching it per thread would
            // pin memory per virtual thread, so it is created per call; at roughly a microsecond
            // this is negligible next to the cost of the log or span it feeds.
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key.spec());
            byte[] digest = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] truncated = new byte[outputBytes];
            System.arraycopy(digest, 0, truncated, 0, outputBytes);
            return prefix + ENCODER.encodeToString(truncated);
        } catch (GeneralSecurityException e) {
            throw new MaskingException("HMAC-SHA-256 is unavailable in this JVM");
        }
    }

    public boolean usesEphemeralKey() {
        return key.isEphemeral();
    }
}
