package ch.raph.datamask.spring;

/**
 * Thrown when a context asks for masking without saying what to key it with.
 *
 * <p>Refusing to start is the point. The alternative — quietly falling back to a key of the
 * library's own — would make every {@code HASH} pseudonym in production reversible by anyone who
 * downloaded the artifact, and nothing about the running system would look wrong.
 *
 * <p>{@link MissingMaskSecretFailureAnalyzer} turns this into the startup message an operator
 * actually reads.
 */
public class MissingMaskSecretException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    MissingMaskSecretException() {
        super("No DataMask secret is configured");
    }
}
