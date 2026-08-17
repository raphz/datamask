package ch.raph.datamask.spring;

/**
 * Thrown when {@code datamask.secret} is configured but is too short to key pseudonymisation with.
 *
 * <p>The sibling of {@link MissingMaskSecretException}, and at least as likely: a team setting the
 * property for the first time reaches for a value they can type. Padding it into an acceptable
 * length would be the same mistake as shipping a default key — {@code HASH} covers inputs small
 * enough to enumerate, so a guessable secret makes every pseudonym reversible while the running
 * system still looks correct.
 *
 * <p>The message says nothing about the configured value, and neither does
 * {@link ShortMaskSecretFailureAnalyzer}. A startup line naming its length or its first characters
 * would be a disclosure of the one key that reverses everything this library issues, written to the
 * logs by the library whose job is to keep such things out of them.
 */
public class ShortMaskSecretException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    ShortMaskSecretException(Throwable cause) {
        super("The configured DataMask secret is too short", cause);
    }
}
