package ch.raph.datamask.domain;

import ch.raph.datamask.api.PiiCategory;
import java.util.Optional;

/**
 * Exchanges a value for a reversible surrogate and back.
 *
 * <p>The port exists because reversibility is an infrastructure decision with real consequences:
 * an in-memory map is right for sanitising an AI prompt and reading the answer back in the same
 * request, and completely wrong for card data, which belongs in a PCI-scoped vault.
 */
public interface TokenVault {

    /** Returns a surrogate for the value. Calling twice with the same value may return the same token. */
    String tokenize(String value, PiiCategory category);

    /** Resolves a surrogate previously issued by this vault, if it is still known and the caller may see it. */
    Optional<String> detokenize(String token);
}
