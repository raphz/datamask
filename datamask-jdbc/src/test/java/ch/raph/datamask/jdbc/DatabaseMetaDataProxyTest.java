package ch.raph.datamask.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.raph.datamask.application.DataMask;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The way out of the wrapper that {@code DatabaseMetaData} used to be.
 *
 * <p>{@code metaData.getConnection()} is specified to return the connection that produced the
 * metadata, and an unproxied metadata object returns the driver's own — so every statement created
 * through it, and every error those statements raise, would leave the masking behind. The database
 * is stubbed here: no server is needed to find out which object comes back.
 */
@DisplayName("A connection's DatabaseMetaData")
class DatabaseMetaDataProxyTest {

    private static final String EMAIL = "john@x.com";

    private final DataSource dataSource = new MaskingDataSource(stubDataSource(), DataMask.withDefaults());

    private static String everythingVisible(Throwable thrown) {
        StringWriter rendered = new StringWriter();
        thrown.printStackTrace(new PrintWriter(rendered));
        return rendered + "\n" + thrown;
    }

    @Test
    @DisplayName("hands back the masking connection rather than the driver's own, which is what keeps a "
            + "statement created through it inside the wrapper")
    void handsBackTheMaskingConnection() throws SQLException {
        Connection connection = dataSource.getConnection();

        assertThat(connection.getMetaData().getConnection()).isSameAs(connection);
    }

    @Test
    @DisplayName("sanitises an error from a statement created through the metadata's connection, which is "
            + "the leak the missing proxy actually caused")
    void sanitisesErrorsFromStatementsCreatedThroughIt() throws SQLException {
        Connection connection = dataSource.getConnection();
        Statement statement = connection.getMetaData().getConnection().createStatement();

        Throwable thrown = catchThrowable(() -> statement.execute("insert into customer (email) values (?)"));

        assertThat(everythingVisible(thrown)).doesNotContain(EMAIL);
    }

    @Test
    @DisplayName("sanitises an error raised by a metadata call itself")
    void sanitisesErrorsFromMetadataCalls() throws SQLException {
        DatabaseMetaData metaData = dataSource.getConnection().getMetaData();

        Throwable thrown = catchThrowable(() -> metaData.getTables(null, null, "customer", null));

        assertThat(everythingVisible(thrown)).doesNotContain(EMAIL);
    }

    @Test
    @DisplayName("sanitises an error raised while reading a metadata result set, which in cursor mode is "
            + "where it arrives")
    void sanitisesErrorsFromMetadataResultSets() throws SQLException {
        ResultSet columns = dataSource.getConnection().getMetaData().getColumns(null, null, "customer", null);

        Throwable thrown = catchThrowable(columns::next);

        assertThat(everythingVisible(thrown)).doesNotContain(EMAIL);
    }

    /**
     * A JDBC stack that fails the way a database does. {@code getTables} and {@code execute} raise a
     * unique-constraint violation quoting the offending value, which is what the assertions look for.
     */
    private static DataSource stubDataSource() {
        Connection[] connection = new Connection[1];
        ResultSet resultSet = (ResultSet) stub(ResultSet.class, method -> switch (method.getName()) {
            case "next" -> throw violation();
            default -> null;
        });
        DatabaseMetaData metaData = (DatabaseMetaData) stub(DatabaseMetaData.class, method -> switch (method.getName()) {
            case "getConnection" -> connection[0];
            case "getTables" -> throw violation();
            case "getColumns" -> resultSet;
            default -> null;
        });
        Statement statement = (Statement) stub(Statement.class, method -> switch (method.getName()) {
            case "execute" -> throw violation();
            case "getConnection" -> connection[0];
            default -> null;
        });
        connection[0] = (Connection) stub(Connection.class, method -> switch (method.getName()) {
            case "getMetaData" -> metaData;
            case "createStatement" -> statement;
            default -> null;
        });
        return (DataSource) stub(DataSource.class, method -> connection[0]);
    }

    /** The message MySQL answers a duplicate with, value and all. */
    private static SQLException violation() {
        return new SQLException("Duplicate entry '" + EMAIL + "' for key 'customer.email_uq'", "23000", 1062);
    }

    private static Object stub(Class<?> jdbcInterface, Answers answers) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> "stub:" + jdbcInterface.getSimpleName();
                };
            }
            Object answer = answers.answer(method);
            return answer != null ? answer : defaultValue(method.getReturnType());
        };
        return Proxy.newProxyInstance(
                DatabaseMetaDataProxyTest.class.getClassLoader(), new Class<?>[] {jdbcInterface}, handler);
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
