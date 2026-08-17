package ch.raph.datamask.spring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.diagnostics.FailureAnalysis;

@DisplayName("A secret that is present but too short explains itself at startup, without repeating any of it")
class ShortMaskSecretFailureAnalyzerTest {

    /** Short enough that MaskKey rejects it, and distinctive enough to search the report for. */
    private static final String SHORT_SECRET = "hunter2";

    private final ShortMaskSecretFailureAnalyzer analyzer = new ShortMaskSecretFailureAnalyzer();

    private static ShortMaskSecretException failure() {
        return new ShortMaskSecretException(
                new IllegalArgumentException("DataMask secret must be at least 16 bytes; a short secret makes "
                        + "pseudonyms recoverable by brute force"));
    }

    @Test
    @DisplayName("names the property to set and the requirement, in the shape the missing-secret message uses")
    void describesTheFix() {
        ShortMaskSecretException cause = failure();

        FailureAnalysis analysis = analyzer.analyze(cause, cause);

        assertThat(analysis.getDescription()).contains("too short");
        assertThat(analysis.getAction())
                .contains("datamask.secret")
                .contains("at least 16 bytes")
                .contains("datamask.ephemeral-key=true")
                .contains("datamask.enabled=false");
        assertThat(analysis.getCause()).isSameAs(cause);
    }

    @Test
    @DisplayName("says why a short secret is not padded out, because that is the question the message provokes")
    void explainsWhyItIsNotStretched() {
        ShortMaskSecretException cause = failure();

        assertThat(analyzer.analyze(cause, cause).getAction())
                .contains("does not stretch")
                .contains("reversible");
    }

    @Test
    @DisplayName("repeats no part of the configured secret and no measurement of it, because the report is a "
            + "log line about the one key that reverses every pseudonym this library issues")
    void echoesNothingAboutTheValue() {
        ShortMaskSecretException cause = failure();

        FailureAnalysis analysis = analyzer.analyze(cause, cause);
        String report = analysis.getDescription() + '\n' + analysis.getAction() + '\n' + cause.getMessage();

        assertThat(report).doesNotContain(SHORT_SECRET);
        for (int length = 2; length <= SHORT_SECRET.length(); length++) {
            assertThat(report).as("a prefix of the secret").doesNotContain(SHORT_SECRET.substring(0, length));
        }
        // The only number in the report is the requirement, which was already public.
        assertThat(report.replace("16", "")).doesNotContainPattern("[0-9]");
    }
}
