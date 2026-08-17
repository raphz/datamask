package ch.raph.datamask.infrastructure.vault;

import ch.raph.datamask.api.PiiCategory;
import ch.raph.datamask.domain.TokenVault;
import java.util.Optional;

/**
 * The vault an application gets when it configured none: it refuses to issue tokens.
 *
 * <p>Tokenisation is the one masking strategy that keeps the original value. Handing an unconfigured
 * application a working in-memory vault made {@code TOKENIZE} look like it worked while quietly
 * choosing the worst possible answers to questions nobody had asked — raw PII sitting in a heap map
 * for the life of the process, tokens that stop resolving at the next restart, and a
 * {@code detokenize} that reverses masking for any caller that can reach the bean.
 *
 * <p>Refusing is not a crash. The engine treats a masker failure the way it treats every other one:
 * the value is redacted and {@code MaskingObserver.onFailure} is told, so a deployment that reached
 * for {@code TOKENIZE} without a vault gets full masking plus a signal, never disclosure. Configure
 * one deliberately with {@code DataMask.builder().vault(...)} — {@link InMemoryTokenVault} for the
 * request-scoped round trip it was written for, something PCI-scoped for card data.
 */
public final class RejectingTokenVault implements TokenVault {

    public static final RejectingTokenVault INSTANCE = new RejectingTokenVault();

    private RejectingTokenVault() {}

    @Override
    public String tokenize(String value, PiiCategory category) {
        throw new IllegalStateException("TOKENIZE needs a TokenVault and none is configured; register one with "
                + "DataMask.builder().vault(...) — InMemoryTokenVault for a request-scoped round trip, "
                + "a PCI-scoped vault for card data. The value was redacted instead.");
    }

    @Override
    public Optional<String> detokenize(String token) {
        return Optional.empty();
    }
}
