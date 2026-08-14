package ch.raph.datamask.infrastructure.vault;

import ch.raph.datamask.api.PiiCategory;
import ch.raph.datamask.domain.TokenVault;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A bounded, in-process vault.
 *
 * <p>Its purpose is the request-scoped round trip: mask a prompt before it goes to a model, then
 * put the real values back into the answer that comes home. It is explicitly <em>not</em> a
 * substitute for a PCI-scoped vault — nothing here is persisted, encrypted at rest, or audited,
 * and the bound means an old mapping can be evicted while its token is still in flight.
 */
public final class InMemoryTokenVault implements TokenVault {

    private static final int DEFAULT_CAPACITY = 10_000;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final SecureRandom random = new SecureRandom();
    private final Map<String, String> tokenToValue;
    private final Map<String, String> valueToToken;

    public InMemoryTokenVault() {
        this(DEFAULT_CAPACITY);
    }

    public InMemoryTokenVault(int capacity) {
        this.tokenToValue = boundedMap(capacity);
        this.valueToToken = new ConcurrentHashMap<>();
    }

    private Map<String, String> boundedMap(int capacity) {
        Map<String, String> lru = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                if (size() > capacity) {
                    valueToToken.remove(eldest.getValue());
                    return true;
                }
                return false;
            }
        };
        return java.util.Collections.synchronizedMap(lru);
    }

    @Override
    public String tokenize(String value, PiiCategory category) {
        if (value == null) {
            return null;
        }
        String existing = valueToToken.get(value);
        if (existing != null) {
            return existing;
        }
        byte[] material = new byte[12];
        random.nextBytes(material);
        String token =
                "tok_" + category.name().toLowerCase(java.util.Locale.ROOT) + "_" + ENCODER.encodeToString(material);
        tokenToValue.put(token, value);
        valueToToken.put(value, token);
        return token;
    }

    @Override
    public Optional<String> detokenize(String token) {
        return token == null ? Optional.empty() : Optional.ofNullable(tokenToValue.get(token));
    }

    /** Forgets every mapping. Call it when a request finishes to bound how long values are held. */
    public void clear() {
        tokenToValue.clear();
        valueToToken.clear();
    }

    public int size() {
        return tokenToValue.size();
    }
}
