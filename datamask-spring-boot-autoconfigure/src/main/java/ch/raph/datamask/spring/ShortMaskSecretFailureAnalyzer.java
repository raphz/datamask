package ch.raph.datamask.spring;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Turns a secret that is present but too short into a startup message that says what to do about it.
 *
 * <p>Worth the class for the same reason its sibling is: this is the other half of the first
 * configuration mistake most teams make, and left alone it surfaces as an
 * {@code IllegalArgumentException} thrown from inside the crypto adapter, which reads like a bug in
 * the library rather than a value that needs to be longer.
 *
 * <p>Nothing here is derived from the configured secret. The only number in the message is the
 * requirement, which was already public in this class.
 */
class ShortMaskSecretFailureAnalyzer extends AbstractFailureAnalyzer<ShortMaskSecretException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, ShortMaskSecretException cause) {
        return new FailureAnalysis(
                "DataMask has a pseudonymisation secret configured, but it is too short to key masking with.",
                """
                Set 'datamask.secret' to at least 16 bytes of key material, read from your secret \
                manager rather than from application.yml.

                DataMask does not stretch a short secret into a longer one. HASH and TOKENIZE are \
                keyed, and the values they cover — an IBAN, a phone number, a card number — come \
                from an input space small enough to enumerate, so a secret short enough to guess \
                makes every pseudonym reversible while nothing about the running system looks wrong.

                Neither this message nor any log line repeats the value you configured, or its \
                length, or any part of it.

                For local development and tests only, remove 'datamask.secret' and set \
                'datamask.ephemeral-key=true' to accept a random per-JVM key. Everything is still \
                masked, but a pseudonym stops being comparable across restarts and across instances.

                To disable DataMask entirely, set 'datamask.enabled=false'.""",
                cause);
    }
}
