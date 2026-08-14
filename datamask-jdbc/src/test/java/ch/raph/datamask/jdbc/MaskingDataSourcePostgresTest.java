package ch.raph.datamask.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ch.raph.datamask.application.DataMask;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.postgresql.util.PSQLException;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The end-to-end case: a real PostgreSQL server, a real unique constraint, a real violation.
 *
 * <p>Every other test in this module builds its exceptions from the wire format. This one does not
 * assume anything — it makes the database reject a duplicate and checks what the driver actually
 * hands back, which is the only way to know the structural rules match what PostgreSQL emits rather
 * than what this code expects it to emit.
 *
 * <p>{@link #leaksWithoutTheWrapper()} is deliberately the first assertion: a test that the value is
 * absent proves nothing unless the same query demonstrably leaks it without the wrapper in place.
 */
@DisplayName("A wrapped DataSource against a real PostgreSQL server")
class MaskingDataSourcePostgresTest {

    private static final String EMAIL = "john@x.com";
    private static final String IBAN = "CH9300762011623852957";

    private static PostgreSQLContainer postgres;
    private static PGSimpleDataSource raw;

    private final DataSource masked = new MaskingDataSource(raw, DataMask.withDefaults());

    @BeforeAll
    static void startDatabase() {
        // A contributor without Docker still gets every other test in the module; CI has Docker, so
        // this one runs there on every build.
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");

        postgres = new PostgreSQLContainer("postgres:17-alpine");
        postgres.start();

        raw = new PGSimpleDataSource();
        raw.setUrl(postgres.getJdbcUrl());
        raw.setUser(postgres.getUsername());
        raw.setPassword(postgres.getPassword());
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void resetSchema() throws SQLException {
        try (Connection connection = raw.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("drop table if exists customer");
            statement.execute("create table customer (id int primary key, email text unique, iban text)");
            statement.execute("insert into customer values (1, '" + EMAIL + "', '" + IBAN + "')");
        }
    }

    private static String everythingVisible(Throwable exception) {
        StringWriter rendered = new StringWriter();
        exception.printStackTrace(new PrintWriter(rendered));
        return rendered + "\n" + exception + "\n" + exception.getMessage();
    }

    private static void insertDuplicate(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("insert into customer values (?, ?, ?)")) {
            statement.setInt(1, 2);
            statement.setString(2, EMAIL);
            statement.setString(3, IBAN);
            statement.executeUpdate();
        }
    }

    @Test
    @DisplayName(
            "leaks the duplicated address without the wrapper, which is what makes the next test " + "worth anything")
    void leaksWithoutTheWrapper() {
        assertThatThrownBy(() -> insertDuplicate(raw))
                .isInstanceOf(PSQLException.class)
                .hasMessageContaining("Key (email)=(" + EMAIL + ") already exists.");
    }

    @Test
    @DisplayName("keeps the duplicated address out of a genuine unique-constraint violation while still "
            + "naming the constraint that rejected it")
    void masksTheOffendingValue() {
        SQLException thrown = catchInsert(masked);

        assertThat(everythingVisible(thrown)).doesNotContain(EMAIL);
        assertThat(thrown.getMessage())
                .contains("duplicate key value violates unique constraint \"customer_email_key\"")
                .contains("Key (email)=(****) already exists.");
    }

    @Test
    @DisplayName("hands back an exception the application can still classify: a PSQLException, SQL state "
            + "23505, and a server error message emptied of the value")
    void keepsTheExceptionUsable() {
        SQLException thrown = catchInsert(masked);

        assertThat((Throwable) thrown).isInstanceOf(PSQLException.class);
        assertThat(thrown.getSQLState()).isEqualTo("23505");
        assertThat(((PSQLException) thrown).getServerErrorMessage().getConstraint())
                .isEqualTo("customer_email_key");
        assertThat(((PSQLException) thrown).getServerErrorMessage().getDetail()).doesNotContain(EMAIL);
    }

    @Test
    @DisplayName("keeps the offending value out of a check-constraint violation, whose detail renders the "
            + "entire failing row")
    void masksAFailingRow() throws SQLException {
        try (Connection connection = raw.getConnection();
                Statement statement = connection.createStatement()) {
            // Satisfied by the row already there, violated by the one the test goes on to insert, so
            // the check fires before the unique index does and the detail renders the whole row.
            statement.execute("alter table customer add constraint single_customer check (id < 2)");
        }

        SQLException thrown = catchInsert(masked);

        assertThat(everythingVisible(thrown)).doesNotContain(EMAIL).doesNotContain(IBAN);
        assertThat(thrown.getMessage()).contains("single_customer").doesNotContain("Failing row contains");
    }

    @Test
    @DisplayName("masks a value echoed by a cast that fails mid-fetch, which arrives from the result set "
            + "rather than from execute")
    void masksErrorsRaisedDuringFetch() throws SQLException {
        try (Connection connection = masked.getConnection();
                Statement statement = connection.createStatement()) {
            statement.setFetchSize(1);
            connection.setAutoCommit(false);

            assertThatThrownBy(() -> {
                        try (ResultSet rows = statement.executeQuery("select email::int from customer")) {
                            while (rows.next()) {
                                rows.getInt(1);
                            }
                        }
                    })
                    .isInstanceOf(SQLException.class)
                    .extracting(MaskingDataSourcePostgresTest::everythingVisible)
                    .asString()
                    .doesNotContain(EMAIL);
        }
    }

    @Test
    @DisplayName("reads rows through the proxies unchanged: the wrapper masks what the database says went "
            + "wrong, never the data the query asked for")
    void doesNotMaskQueryResults() throws SQLException {
        try (Connection connection = masked.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement("select email, iban from customer where id = ?")) {
            statement.setInt(1, 1);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("email")).isEqualTo(EMAIL);
                assertThat(rows.getString("iban")).isEqualTo(IBAN);
            }
        }
    }

    @Test
    @DisplayName("returns the wrapped connection when navigated to from a statement, so a caller cannot "
            + "step outside the wrapper by accident")
    void staysInsideTheWrapper() throws SQLException {
        try (Connection connection = masked.getConnection();
                Statement statement = connection.createStatement()) {
            assertThat(statement.getConnection()).isSameAs(connection);
            // unwrap is the documented way out, and it is honoured.
            assertThat(connection.unwrap(Connection.class)).isNotSameAs(connection);
        }
    }

    @Test
    @DisplayName("passes an error carrying nothing sensitive through as the driver's own exception")
    void leavesCleanErrorsAlone() throws SQLException {
        try (Connection connection = masked.getConnection();
                Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.execute("select * from no_such_table"))
                    .isInstanceOf(PSQLException.class)
                    .hasMessageContaining("no_such_table");
        }
    }

    private static SQLException catchInsert(DataSource dataSource) {
        try {
            insertDuplicate(dataSource);
            throw new AssertionError("expected the duplicate insert to be rejected");
        } catch (SQLException e) {
            return e;
        }
    }
}
