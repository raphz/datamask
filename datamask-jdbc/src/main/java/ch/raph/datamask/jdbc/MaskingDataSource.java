package ch.raph.datamask.jdbc;

import ch.raph.datamask.application.DataMask;
import ch.raph.datamask.application.MaskingEngine;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Objects;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * A {@link DataSource} that takes row values out of the errors the database reports, and masks bind
 * parameters in the statements it logs.
 *
 * {@snippet :
 * DataMask dataMask = DataMask.builder().secret(System.getenv("DATAMASK_SECRET")).build();
 * DataSource dataSource = new MaskingDataSource(hikariDataSource, dataMask);
 * }
 *
 * <p>The leak this closes is a unique-constraint violation. PostgreSQL answers one by echoing the
 * offending row: {@code Detail: Key (email)=(john@x.com) already exists.} That text becomes the
 * exception's message, so it reaches the stack trace, every log line that renders the exception and
 * whatever the logs are shipped to — and unlike a field on a DTO, nobody thinks to look for it. With
 * this wrapper in place the same error reads {@code Key (email)=(****) already exists.}: the
 * constraint, the table and the column all survive, the value does not.
 *
 * <p>Wrap the pool, do not replace it. This is a thin proxy with no pooling behaviour of its own —
 * it forwards every call — so it goes outside HikariCP or whatever pool is already configured and
 * leaves that pool's behaviour untouched.
 *
 * <h2>Statement logging</h2>
 *
 * Set the {@code ch.raph.datamask.jdbc.statement} logger to DEBUG to log each statement with its
 * bind parameters masked. Below that level nothing is recorded and no parameter is examined, so the
 * masking costs nothing when the log is off.
 *
 * <h2>Two behaviours worth knowing about</h2>
 *
 * <p>{@code unwrap} returns the real object, unproxied, as JDBC intends. Code reaching for
 * {@code PGConnection} to run a {@code COPY} needs the driver's own connection, and refusing would
 * break it; exceptions from an object obtained that way are nobody's to sanitise.
 *
 * <p>An exception with nothing to remove is returned exactly as the driver threw it, same instance
 * and same type. Only an error that actually carried a value is replaced. See
 * {@link SqlExceptionSanitizer} for what a replacement looks like.
 */
public final class MaskingDataSource implements DataSource {

    private final DataSource delegate;
    private final JdbcMasking masking;

    public MaskingDataSource(DataSource delegate, DataMask dataMask) {
        this(delegate, Objects.requireNonNull(dataMask, "dataMask").engine());
    }

    public MaskingDataSource(DataSource delegate, MaskingEngine engine) {
        this(delegate, new JdbcMasking(Objects.requireNonNull(engine, "engine")));
    }

    private MaskingDataSource(DataSource delegate, JdbcMasking masking) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.masking = masking;
    }

    /**
     * The same wrapper with result sets left unproxied, for a read path where the proxy's cost has
     * been measured and found to matter.
     *
     * {@snippet :
     * DataSource dataSource = new MaskingDataSource(hikariDataSource, dataMask).withoutResultSetWrapping();
     * }
     *
     * <p><strong>Know what this gives up.</strong> Not every database error arrives at execution.
     * In cursor mode a statement timeout, a cast failing on a stored value or a broken connection
     * surfaces from {@code next()} or a getter, and those exceptions now reach the application
     * exactly as the driver threw them — carrying whatever row value the driver put in the message.
     * Connections, statements, bind parameters and metadata are still wrapped, so the
     * unique-constraint violation this library was written for is still sanitised either way.
     *
     * <p>What it buys is a proxy dispatch per call on the one path whose cost scales with the size
     * of the result: a thousand-row fetch reading ten columns is ten thousand reflective forwards.
     * {@code datamask-benchmarks} measures it; turn this on because that measurement said something
     * about <em>your</em> read path, not on the assumption that it must be expensive.
     */
    public MaskingDataSource withoutResultSetWrapping() {
        return new MaskingDataSource(delegate, masking.withoutResultSetWrapping());
    }

    @Override
    public Connection getConnection() throws SQLException {
        // Sanitised as well: a connection failure quotes the URL and the user it failed as.
        try {
            return JdbcProxies.connection(delegate.getConnection(), masking);
        } catch (SQLException e) {
            throw masking.sanitize(e);
        }
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        try {
            return JdbcProxies.connection(delegate.getConnection(username, password), masking);
        } catch (SQLException e) {
            throw masking.sanitize(e);
        }
    }

    /** The wrapped {@code DataSource}, for a pool that needs configuring after the fact. */
    public DataSource delegate() {
        return delegate;
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        delegate.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return iface.isInstance(this) ? iface.cast(this) : delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this) || delegate.isWrapperFor(iface);
    }
}
