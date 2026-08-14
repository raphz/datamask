package ch.raph.datamask.spring;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Turns a missing secret into a startup message that says what to do about it.
 *
 * <p>Worth the class: this is the one configuration mistake the library refuses to work around, so
 * the first time most teams meet it is a failed deployment. A wall of bean-creation stack trace
 * would send them looking for the ephemeral fall-back that deliberately does not exist.
 */
class MissingMaskSecretFailureAnalyzer extends AbstractFailureAnalyzer<MissingMaskSecretException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, MissingMaskSecretException cause) {
        return new FailureAnalysis(
                "DataMask is on the classpath but no pseudonymisation secret is configured.", """
                Set 'datamask.secret' to at least 16 bytes of key material, read from your secret \
                manager rather than from application.yml.

                DataMask ships no default key on purpose: HASH and TOKENIZE are keyed, and a key \
                published inside a library would make every pseudonym reversible by anyone holding \
                the artifact.

                For local development and tests only, set 'datamask.ephemeral-key=true' to accept a \
                random per-JVM key. Everything is still masked, but a pseudonym stops being \
                comparable across restarts and across instances.

                To disable DataMask entirely, set 'datamask.enabled=false'.""", cause);
    }
}
