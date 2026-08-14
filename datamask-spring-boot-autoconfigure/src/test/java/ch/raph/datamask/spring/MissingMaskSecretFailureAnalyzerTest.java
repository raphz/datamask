package ch.raph.datamask.spring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.diagnostics.FailureAnalysis;

@DisplayName("The one configuration mistake this library refuses to work around explains itself at startup")
class MissingMaskSecretFailureAnalyzerTest {

    private final MissingMaskSecretFailureAnalyzer analyzer = new MissingMaskSecretFailureAnalyzer();

    @Test
    @DisplayName("names the property to set, and the escape hatch that is only for development")
    void describesTheFix() {
        MissingMaskSecretException cause = new MissingMaskSecretException();

        FailureAnalysis analysis = analyzer.analyze(cause, cause);

        assertThat(analysis.getDescription()).contains("no pseudonymisation secret is configured");
        assertThat(analysis.getAction())
                .contains("datamask.secret")
                .contains("datamask.ephemeral-key=true")
                .contains("datamask.enabled=false");
        assertThat(analysis.getCause()).isSameAs(cause);
    }

    @Test
    @DisplayName("says why there is no default key, because that is the question the message provokes")
    void explainsWhyThereIsNoDefault() {
        MissingMaskSecretException cause = new MissingMaskSecretException();

        assertThat(analyzer.analyze(cause, cause).getAction())
                .contains("no default key")
                .contains("reversible");
    }
}
