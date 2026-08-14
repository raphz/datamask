package ch.raph.datamask.domain;

/**
 * Produces a deterministic, irreversible surrogate for a value.
 *
 * <p>Deterministic matters: it is what allows an operator to grep every log line belonging to one
 * customer without ever seeing who that customer is. Irreversible matters for the obvious reason.
 * Implementations must therefore be keyed — a bare digest of an IBAN or a phone number is
 * enumerable in seconds and is not pseudonymisation under GDPR Article 4(5).
 */
public interface Pseudonymizer {

    String pseudonymize(String value);
}
