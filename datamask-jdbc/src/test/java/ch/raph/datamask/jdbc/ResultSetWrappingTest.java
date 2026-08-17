package ch.raph.datamask.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.raph.datamask.application.DataMask;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one part of the wrapper that can be turned off, and exactly what turning it off costs.
 *
 * <p>Result-set proxying is the only cost here that scales with the size of a result rather than
 * with the number of statements, so it is the only one worth an escape hatch. The hatch is not free,
 * and these tests are what says so plainly: with it open, an error that surfaces during a fetch
 * reaches the application carrying whatever the driver put in the message.
 */
@DisplayName("Result-set wrapping")
class ResultSetWrappingTest {

    private static final String EMAIL = "john@x.com";

    private final MaskingDataSource wrapping = new MaskingDataSource(stubDataSource(), DataMask.withDefaults());

    @Test
    @DisplayName("sanitises an error raised during a fetch, which in cursor mode is where it arrives")
    void sanitisesFetchErrorsByDefault() throws SQLException {
        ResultSet rows = wrapping.getConnection().createStatement().executeQuery("select email from customer");

        Throwable thrown = catchThrowable(rows::next);

        assertThat(thrown).hasMessageNotContaining(EMAIL);
    }

    @Test
    @DisplayName("hands back the driver's own result set once wrapping is off")
    void handsBackTheRawResultSet() throws SQLException {
        ResultSet rows = wrapping.withoutResultSetWrapping()
                .getConnection()
                .createStatement()
                .executeQuery("select email from customer");

        assertThat(Proxy.isProxyClass(rows.getClass())).isTrue();
        // The stub is itself a proxy, so identity is what distinguishes "not wrapped" from
        // "wrapped": the handler behind it is the test's, not the library's.
        assertThat(Proxy.getInvocationHandler(rows).getClass().getName())
                .startsWith(ResultSetWrappingTest.class.getName());
    }

    @Test
    @DisplayName("and a fetch error then reaches the caller as the driver threw it — the cost of the hatch")
    void fetchErrorsAreUnsanitisedOnceOff() throws SQLException {
        ResultSet rows = wrapping.withoutResultSetWrapping()
                .getConnection()
                .createStatement()
                .executeQuery("select email from customer");

        Throwable thrown = catchThrowable(rows::next);

        assertThat(thrown).hasMessageContaining(EMAIL);
    }

    @Test
    @DisplayName("while the error this library was written for is still sanitised either way")
    void stillSanitisesExecutionErrors() throws SQLException {
        Statement statement =
                wrapping.withoutResultSetWrapping().getConnection().createStatement();

        Throwable thrown = catchThrowable(() -> statement.execute("insert into customer (email) values (?)"));

        assertThat(thrown).hasMessageNotContaining(EMAIL);
    }

    /** A stack that fails at execution and again during the fetch, the way a database does. */
    private static DataSource stubDataSource() {
        ResultSet resultSet = (ResultSet) stub(ResultSet.class, method -> switch (method.getName()) {
            case "next" -> throw violation();
            default -> null;
        });
        Statement statement = (Statement) stub(Statement.class, method -> switch (method.getName()) {
            case "execute" -> throw violation();
            case "executeQuery" -> resultSet;
            default -> null;
        });
        Connection connection = (Connection) stub(Connection.class, method -> switch (method.getName()) {
            case "createStatement" -> statement;
            default -> null;
        });
        return (DataSource) stub(DataSource.class, method -> connection);
    }

    /** The message MySQL answers a duplicate with, value and all. */
    private static SQLException violation() {
        return new SQLException("Duplicate entry '" + EMAIL + "' for key 'customer.email_uq'", "23000", 1062);
    }

    private static Object stub(Class<?> jdbcInterface, Answers answers) {
        InvocationHandler handler = new StubHandler(jdbcInterface, answers);
        return Proxy.newProxyInstance(
                ResultSetWrappingTest.class.getClassLoader(), new Class<?>[] {jdbcInterface}, handler);
    }

    /** Named rather than a lambda, so a test can tell whose handler is behind a proxy. */
    private record StubHandler(Class<?> jdbcInterface, Answers answers) implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> "stub:" + jdbcInterface.getSimpleName();
                };
            }
            Object answer = answers.answer(method);
            return answer != null ? answer : defaultValue(method.getReturnType());
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        return type == long.class ? 0L : null;
    }

    @FunctionalInterface
    private interface Answers {
        Object answer(Method method) throws SQLException;
    }
}
