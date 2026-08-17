package ch.raph.datamask.benchmarks;

import ch.raph.datamask.jdbc.MaskingDataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * What the JDBC wrapper costs on the read path.
 *
 * <p>The improvements document listed this as a suspicion: a JDK proxy forwards through
 * {@code Method.invoke} with a boxed argument array, and a result set is the one object in the
 * wrapper whose calls scale with the size of the result rather than with the number of statements.
 * A thousand-row fetch reading ten columns is ten thousand of those forwards. Whether that is worth
 * an escape hatch is a question with a number, and this is the number.
 *
 * <p><strong>The database is a stub, and that is the measurement.</strong> {@code next()} and
 * {@code getString()} answer from a field, so what is left between the proxied and unproxied runs is
 * the forwarding and nothing else. Against a real driver — parsing, sockets, a network — the same
 * absolute cost sits under work that is orders of magnitude larger, which is precisely why it has to
 * be isolated to be seen at all. Read the ratio here as an upper bound on what wrapping costs a
 * fetch, never as what it costs a query.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
@State(Scope.Benchmark)
public class JdbcProxyBenchmark {

    /** Ten columns, which is an ordinary row rather than a wide one. */
    private static final int COLUMNS = 10;

    private ResultSet raw;
    private ResultSet wrapped;
    private ResultSet unwrappedByPolicy;

    @Setup
    public void setUp() throws SQLException {
        DataSource stub = stubDataSource();
        raw = stub.getConnection().createStatement().executeQuery(SQL);

        DataSource masking = new MaskingDataSource(stubDataSource(), Fixtures.dataMask());
        wrapped = masking.getConnection().createStatement().executeQuery(SQL);

        DataSource hatchOpen = new MaskingDataSource(stubDataSource(), Fixtures.dataMask()).withoutResultSetWrapping();
        unwrappedByPolicy = hatchOpen.getConnection().createStatement().executeQuery(SQL);
    }

    private static final String SQL = "select * from customer";

    /** The floor: the driver's own result set, read directly. */
    @Benchmark
    public int rawResultSetRow() throws SQLException {
        return readRow(raw);
    }

    /** The same row through the masking wrapper — one reflective forward per call. */
    @Benchmark
    public int proxiedResultSetRow() throws SQLException {
        return readRow(wrapped);
    }

    /**
     * And through the wrapper with {@code withoutResultSetWrapping()}. It should land on the raw
     * figure: the statement is still proxied, the result set it handed back is not.
     */
    @Benchmark
    public int unwrappedResultSetRow() throws SQLException {
        return readRow(unwrappedByPolicy);
    }

    private static int readRow(ResultSet rows) throws SQLException {
        rows.next();
        int length = 0;
        for (int column = 1; column <= COLUMNS; column++) {
            length += rows.getString(column).length();
        }
        return length;
    }

    /**
     * A JDBC stack that answers without doing any work, so the difference between the benchmarks is
     * the forwarding. Values are invented and shaped like the columns a customer table carries.
     */
    private static DataSource stubDataSource() {
        String value = "row-value-0000";
        ResultSet resultSet = (ResultSet) stub(ResultSet.class, method -> switch (method.getName()) {
            case "next" -> Boolean.TRUE;
            case "getString" -> value;
            default -> null;
        });
        Statement statement = (Statement) stub(Statement.class, method -> switch (method.getName()) {
            case "executeQuery" -> resultSet;
            default -> null;
        });
        Connection connection = (Connection) stub(Connection.class, method -> switch (method.getName()) {
            case "createStatement" -> statement;
            default -> null;
        });
        return (DataSource) stub(DataSource.class, method -> connection);
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
                JdbcProxyBenchmark.class.getClassLoader(), new Class<?>[] {jdbcInterface}, handler);
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
