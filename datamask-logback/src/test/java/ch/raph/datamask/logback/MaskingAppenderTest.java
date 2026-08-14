package ch.raph.datamask.logback;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import ch.raph.datamask.application.DataMask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Masking every event on its way to the appenders underneath")
class MaskingAppenderTest {

    private static final String SECRET = "a-test-secret-of-sufficient-length";
    private static final String IBAN = "CH9300762011623852957";
    private static final String EMAIL = "john.doe@example.com";

    private LoggerContext context;
    private ListAppender<ILoggingEvent> captured;
    private MaskingAppender masking;

    @BeforeEach
    void setUp() {
        context = new LoggerContext();
        context.setName("test");
        captured = new ListAppender<>();
        captured.setName("CAPTURED");
        captured.setContext(context);
        captured.start();

        masking = new MaskingAppender();
        masking.setName("MASKED");
        masking.setContext(context);
        masking.addAppender(captured);
    }

    @AfterEach
    void tearDown() {
        DataMaskLogback.uninstall();
        masking.stop();
        context.stop();
    }

    @Test
    @DisplayName("forwards a masked event to the appenders underneath, leaving their configuration untouched")
    void forwardsMaskedEvents() {
        masking.setDataMask(DataMask.builder().secret(SECRET).build());
        Logger logger = start();

        logger.info("crediting {}", IBAN);

        assertThat(captured.list).hasSize(1);
        assertThat(captured.list.getFirst().getFormattedMessage())
                .doesNotContain(IBAN)
                .isEqualTo("crediting CH93 **** **** **** *295 7");
    }

    @Test
    @DisplayName("masks an exception message on the way through, not only the message")
    void masksExceptionMessages() {
        masking.setDataMask(DataMask.builder().secret(SECRET).build());
        Logger logger = start();

        logger.error("insert failed", new IllegalStateException("Key (email)=(" + EMAIL + ") already exists"));

        assertThat(captured.list.getFirst().getThrowableProxy().getMessage()).doesNotContain(EMAIL);
    }

    @Test
    @DisplayName("uses the instance installed for logback when nothing was set on the appender")
    void usesTheInstalledInstance() {
        DataMaskLogback.install(DataMask.builder().secret(SECRET).build());
        Logger logger = start();

        logger.info("crediting {}", IBAN);

        assertThat(captured.list.getFirst().getFormattedMessage()).doesNotContain(IBAN);
    }

    @Test
    @DisplayName("picks up an instance installed after logging began, since beans outlive logback's own startup")
    void picksUpALateInstall() {
        Logger logger = start();
        logger.info("crediting {}", IBAN);

        DataMaskLogback.install(DataMask.builder().secret(SECRET).build());
        logger.info("crediting {}", IBAN);

        assertThat(captured.list).hasSize(2);
        assertThat(captured.list).allSatisfy(event -> assertThat(event.getFormattedMessage())
                .doesNotContain(IBAN));
    }

    @Test
    @DisplayName("masks with strict defaults when nothing is configured at all, rather than passing the value through")
    void masksWithTheFallback() {
        Logger logger = start();

        logger.info("crediting {}", IBAN);

        assertThat(captured.list.getFirst().getFormattedMessage()).doesNotContain(IBAN);
    }

    @Test
    @DisplayName("says so loudly when it falls back, because an ephemeral key makes pseudonyms incomparable")
    void reportsTheFallback() {
        Logger logger = start();

        logger.info("crediting {}", IBAN);

        assertThat(context.getStatusManager().getCopyOfStatusList())
                .anySatisfy(status -> assertThat(status.getMessage()).contains("ephemeral key"));
    }

    @Test
    @DisplayName("builds its own engine from a secret, for a deployment configured entirely in XML")
    void buildsFromASecret() {
        masking.setSecret(SECRET);
        Logger logger = start();

        logger.info("crediting {}", IBAN);

        assertThat(captured.list.getFirst().getFormattedMessage()).doesNotContain(IBAN);
    }

    @Test
    @DisplayName("keeps masking under a rejected secret rather than dropping every log line")
    void survivesARejectedSecret() {
        masking.setSecret("too-short");
        Logger logger = start();

        logger.info("crediting {}", IBAN);

        assertThat(masking.isStarted()).isTrue();
        assertThat(captured.list.getFirst().getFormattedMessage()).doesNotContain(IBAN);
        assertThat(context.getStatusManager().getCopyOfStatusList())
                .anySatisfy(status -> assertThat(status.getMessage()).contains("rejected"));
    }

    @Test
    @DisplayName("refuses to start with nothing attached, rather than silently discarding every event")
    void refusesToStartWithoutAppenders() {
        MaskingAppender orphan = new MaskingAppender();
        orphan.setName("ORPHAN");
        orphan.setContext(context);

        orphan.start();

        assertThat(orphan.isStarted()).isFalse();
        assertThat(context.getStatusManager().getCopyOfStatusList())
                .anySatisfy(status -> assertThat(status.getMessage()).contains("No appender-ref"));
    }

    @Test
    @DisplayName("stops the appenders underneath it, so a shutdown flushes them")
    void stopsNestedAppenders() {
        masking.setDataMask(DataMask.builder().secret(SECRET).build());
        start();

        masking.stop();

        assertThat(captured.isStarted()).isFalse();
        assertThat(masking.getAppender("CAPTURED")).isNull();
    }

    private Logger start() {
        masking.start();
        Logger logger = context.getLogger("ch.example.PaymentService");
        logger.addAppender(masking);
        return logger;
    }
}
