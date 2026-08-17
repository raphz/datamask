package ch.raph.datamask.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import ch.raph.datamask.api.MaskStrategy;
import ch.raph.datamask.api.PiiCategory;
import ch.raph.datamask.application.DataMask;
import ch.raph.datamask.domain.MaskingObserver;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * What the statement log is allowed to contain.
 *
 * <p>A log line is the destination of every leak this module is about, so it is asserted on directly
 * rather than trusted. The database is stubbed here on purpose: no server is needed to find out what
 * gets written, and the stub keeps these tests running for a contributor without Docker.
 */
@DisplayName("Logging a statement's bind parameters")
class StatementLoggingTest {

    private static final String CATEGORY = "ch.raph.datamask.jdbc.statement";
    private static final String EMAIL = "john@x.com";
    private static final String IBAN = "CH9300762011623852957";
    private static final String PAN = "4111111111111111";

    private ch.qos.logback.classic.Logger logger;
    private ListAppender<ILoggingEvent> captured;
    private DataSource dataSource;

    @BeforeEach
    void captureTheLog() {
        logger = ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(CATEGORY);
        logger.setLevel(Level.DEBUG);
        captured = new ListAppender<>();
        captured.start();
        logger.addAppender(captured);

        dataSource = new MaskingDataSource(stubDataSource(), DataMask.withDefaults());
    }

    @AfterEach
    void releaseTheLog() {
        logger.detachAppender(captured);
        logger.setLevel(null);
    }

    private String loggedLines() {
        return captured.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", (a, b) -> a + "\n" + b);
    }

    private void insert(Object... values) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement("insert into customer (email, iban, id) values (?, ?, ?)")) {
            for (int i = 0; i < values.length; i++) {
                statement.setObject(i + 1, values[i]);
            }
            statement.executeUpdate();
        }
    }

    @Test
    @DisplayName("writes the SQL and the parameter positions, so a query is still identifiable")
    void logsTheStatement() throws SQLException {
        insert(EMAIL, IBAN, 7);

        assertThat(loggedLines()).contains("insert into customer (email, iban, id) values (?, ?, ?)");
        assertThat(loggedLines()).contains("1=").contains("2=").contains("3=");
    }

    @Test
    @DisplayName("never writes a bind parameter verbatim — there is no annotation to consult down here, "
            + "and guessing which values are harmless is how a log ends up printing an account number")
    void neverLogsARawValue() throws SQLException {
        insert(EMAIL, IBAN, 7);

        assertThat(loggedLines()).doesNotContain(EMAIL).doesNotContain(IBAN);
    }

    @Test
    @DisplayName("keeps a recognised value's shape, so an IBAN still reads as an IBAN across log lines "
            + "without being disclosed")
    void keepsTheShapeOfRecognisedValues() throws SQLException {
        insert(EMAIL, IBAN, 7);

        assertThat(loggedLines()).doesNotContain(IBAN).contains("CH93");
    }

    @Test
    @DisplayName("reveals only the last four digits of a card number, as PCI-DSS requires")
    void masksCardNumbers() throws SQLException {
        insert(PAN, IBAN, 7);

        assertThat(loggedLines()).doesNotContain(PAN).contains("1111");
    }

    @Test
    @DisplayName("renders a value it does not recognise as the placeholder rather than as itself, because "
            + "unrecognised means unknown")
    void redactsUnrecognisedValues() throws SQLException {
        insert("Mustermann-9910-INTERNAL", IBAN, 7);

        assertThat(loggedLines()).doesNotContain("Mustermann").contains("****");
    }

    @Test
    @DisplayName("renders a number as its type alone: a customer id, a balance and a date of birth are all "
            + "PII and none has a partial form that is safe by default")
    void rendersNonTextAsItsTypeOnly() throws SQLException {
        insert(EMAIL, IBAN, 4242);

        assertThat(loggedLines()).doesNotContain("4242").contains("<Integer>");
    }

    @Test
    @DisplayName("distinguishes a null parameter, which discloses nothing and is worth seeing")
    void rendersNullAsNull() throws SQLException {
        insert(EMAIL, null, 7);

        assertThat(loggedLines()).contains("2=null");
    }

    @Test
    @DisplayName("writes nothing at all when the category is off, and examines no parameter either — the "
            + "masking cost is only paid by someone who asked for the log")
    void logsNothingWhenTheCategoryIsOff() throws SQLException {
        logger.setLevel(Level.INFO);

        insert(EMAIL, IBAN, 7);

        assertThat(captured.list).isEmpty();
    }

    @Test
    @DisplayName("names each parameter's own site — jdbc:param/<index> — so an alert on unannotated PII "
            + "says which parameter leaked rather than only that something did")
    void reportsEachParameterAtItsOwnSite() throws SQLException {
        List<String> reported = new ArrayList<>();
        dataSource = new MaskingDataSource(
                stubDataSource(),
                DataMask.builder()
                        .observer(new MaskingObserver() {
                            @Override
                            public void onUnannotatedPii(String path, PiiCategory category, String detector) {
                                reported.add(path + " " + category);
                            }
                        })
                        .build());

        insert(EMAIL, IBAN, 7);

        assertThat(reported).contains("jdbc:param/1 EMAIL", "jdbc:param/2 IBAN");
    }

    @Test
    @DisplayName("reports an unrecognised parameter as a redaction against its own site too, so the "
            + "parameter that was thrown away is still identifiable")
    void reportsAnUnrecognisedParameterAtItsOwnSite() throws SQLException {
        List<String> reported = new ArrayList<>();
        dataSource = new MaskingDataSource(
                stubDataSource(),
                DataMask.builder()
                        .observer(new MaskingObserver() {
                            @Override
                            public void onMasked(String path, PiiCategory category, MaskStrategy strategy) {
                                reported.add(path + " " + category + " " + strategy);
                            }
                        })
                        .build());

        insert("Mustermann-9910-INTERNAL", IBAN, 7);

        assertThat(reported).contains("jdbc:param/1 UNSPECIFIED REDACT");
    }

    /**
     * A JDBC stack that does nothing. Built with a proxy rather than by implementing three interfaces
     * of a hundred methods each, for the same reason the wrapper itself is.
     */
    private static DataSource stubDataSource() {
        return (DataSource) stub(DataSource.class);
    }

    private static Object stub(Class<?> jdbcInterface) {
        InvocationHandler handler = (proxy, method, args) -> {
            Class<?> returnType = method.getReturnType();
            if (Connection.class == returnType) {
                return stub(Connection.class);
            }
            if (PreparedStatement.class.isAssignableFrom(returnType) && returnType != Object.class) {
                return stub(returnType);
            }
            return defaultValue(returnType);
        };
        return Proxy.newProxyInstance(
                StatementLoggingTest.class.getClassLoader(), new Class<?>[] {jdbcInterface}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == void.class) {
            return null;
        }
        return List.class == type ? List.of() : null;
    }
}
