package ch.raph.datamask.log4j2;

import static org.assertj.core.api.Assertions.assertThat;

import ch.raph.datamask.application.DataMask;
import ch.raph.datamask.log4j2.testdomain.Banking;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.config.DefaultConfiguration;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.logging.log4j.message.SimpleMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Masking the message from inside a layout")
class MaskingMessagePatternConverterTest {

    private static final String SECRET = "a-test-secret-of-sufficient-length";
    private static final String IBAN = "CH9300762011623852957";
    private static final String EMAIL = "john.doe@example.com";
    private static final String LOGGER = "ch.example.PaymentService";

    @BeforeEach
    void install() {
        DataMaskLog4j2.install(DataMask.builder().secret(SECRET).build());
    }

    @AfterEach
    void uninstall() {
        DataMaskLog4j2.uninstall();
    }

    @Test
    @DisplayName("resolves %maskedMessage from the plugin descriptor and masks what the layout writes")
    void masksThroughTheLayout() {
        String rendered = render("%maskedMessage", event("crediting {}", IBAN));

        assertThat(rendered).doesNotContain(IBAN).isEqualTo("crediting CH93 **** **** **** *295 7");
    }

    @Test
    @DisplayName("masks a declared field of a parameter, the same decision the rewrite policy makes")
    void masksDeclaredFields() {
        LogEvent event = event("customer {}", new Banking.Customer(new Banking.Email(EMAIL), IBAN, "CH"));

        String rendered = render("%maskedMessage", event);

        assertThat(rendered).doesNotContain(IBAN).doesNotContain(EMAIL);
    }

    @Test
    @DisplayName("masks a concatenated message, which no parameter declared anything about")
    void masksConcatenatedMessages() {
        LogEvent event = new Log4jLogEvent.Builder()
                .setLoggerName(LOGGER)
                .setLevel(Level.INFO)
                .setMessage(new SimpleMessage("payment from " + IBAN))
                .build();

        assertThat(render("%maskedMessage", event)).doesNotContain(IBAN);
    }

    @Test
    @DisplayName("is also reachable as %maskedMsg, for a pattern that reads better that way")
    void masksUnderTheShortKey() {
        assertThat(render("%maskedMsg", event("crediting {}", IBAN))).doesNotContain(IBAN);
    }

    @Test
    @DisplayName("leaves the rest of the pattern alone, so a layout keeps rendering what it did")
    void keepsTheRestOfThePattern() {
        String rendered = render("%level %logger - %maskedMessage", event("crediting {}", IBAN));

        assertThat(rendered).startsWith("INFO " + LOGGER + " - ").doesNotContain(IBAN);
    }

    @Test
    @DisplayName("still masks with no DataMask installed, under an ephemeral key rather than not at all")
    void masksWithoutAnInstalledInstance() {
        DataMaskLog4j2.uninstall();

        assertThat(render("%maskedMessage", event("crediting {}", IBAN))).doesNotContain(IBAN);
    }

    private static String render(String pattern, LogEvent event) {
        return PatternLayout.newBuilder()
                .withConfiguration(new DefaultConfiguration())
                .withPattern(pattern)
                .build()
                .toSerializable(event);
    }

    private static LogEvent event(String format, Object... parameters) {
        return new Log4jLogEvent.Builder()
                .setLoggerName(LOGGER)
                .setLevel(Level.INFO)
                .setMessage(new ParameterizedMessage(format, parameters))
                .build();
    }
}
