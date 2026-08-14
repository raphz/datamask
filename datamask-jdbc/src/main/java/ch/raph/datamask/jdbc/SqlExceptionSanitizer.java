package ch.raph.datamask.jdbc;

import ch.raph.datamask.application.DataMask;
import ch.raph.datamask.application.MaskingEngine;
import ch.raph.datamask.domain.FailureMode;
import ch.raph.datamask.domain.MaskingException;
import ch.raph.datamask.domain.MaskingObserver;
import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTransactionRollbackException;
import java.util.Objects;

/**
 * Takes the row values out of a database exception.
 *
 * {@snippet :
 * SqlExceptionSanitizer sanitizer = new SqlExceptionSanitizer(dataMask);
 * try {
 *     repository.save(customer);
 * } catch (SQLException e) {
 *     throw sanitizer.sanitize(e);
 * }
 * }
 *
 * <p>A unique-constraint violation is the case that matters. PostgreSQL echoes the offending row
 * back verbatim — {@code Detail: Key (email)=(john@x.com) already exists.} — and that string is
 * then the exception's message, so it reaches the stack trace, every log line that renders it and
 * whatever error tracker the logs ship to. It is an ordinary leak, it happens on a code path
 * everyone has, and nobody goes looking for it.
 *
 * <p>Usable on its own, in an exception handler or a Hibernate listener. {@link MaskingDataSource}
 * simply applies it to everything a connection throws.
 *
 * <h2>What survives</h2>
 *
 * The constraint name, the table, the column, the SQL state and the stack trace: everything needed
 * to know what failed, without the value that failed. A rewritten error reads
 * {@code Key (email)=(****) already exists.}
 *
 * <h2>What is returned</h2>
 *
 * The same instance when there was nothing to remove, which is the common case. Otherwise a
 * replacement — a real {@code PSQLException} when the driver is present, else the standard
 * {@link SQLException} subclass matching the SQL state, so {@code catch
 * (SQLIntegrityConstraintViolationException e)} and Spring's exception translation keep working.
 * The original object is never kept as a cause: it holds the raw text, and a cause is printed.
 *
 * <p>Thread-safe.
 */
public final class SqlExceptionSanitizer {

    /**
     * Loading the driver-specific class is deferred until this is known to be true, which is what
     * keeps {@code org.postgresql} an optional dependency rather than a required one.
     */
    private static final boolean POSTGRES_DRIVER_PRESENT = isPresent("org.postgresql.util.PSQLException");

    /**
     * A chain this long is a bug in something, not a real error. Bounding the walk keeps a
     * self-referential chain from recursing forever, and the fail-closed way to stop is to stop
     * disclosing rather than to hand back what has not been looked at.
     */
    private static final int MAX_CHAIN_LENGTH = 16;

    private final MaskingEngine engine;
    private final MaskingObserver observer;
    private final SqlErrorText text;

    public SqlExceptionSanitizer(DataMask dataMask) {
        this(Objects.requireNonNull(dataMask, "dataMask").engine());
    }

    public SqlExceptionSanitizer(MaskingEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.observer = engine.observer();
        this.text = new SqlErrorText(engine, observer);
    }

    /** The exception with row values removed, or the same instance when it carried none. */
    public SQLException sanitize(SQLException exception) {
        Objects.requireNonNull(exception, "exception");
        try {
            return (SQLException) rewrite(exception, "jdbc:error", 0);
        } catch (MaskingException e) {
            throw e;
        } catch (RuntimeException e) {
            // The sanitiser itself failed. Returning the original would turn a bug in this library
            // into the disclosure it exists to prevent, so the exception is reduced to nothing but
            // its SQL state. Under FailureMode.THROW the bug is surfaced instead, which is what a
            // test run wants.
            observer.onFailure("jdbc:error", e);
            if (engine.policy().failureMode() == FailureMode.THROW) {
                throw e;
            }
            return redactedEntirely(exception);
        }
    }

    /**
     * Sanitises one link and everything reachable from it, returning the same instance when nothing
     * in that whole subtree needed changing.
     *
     * <p>A {@link SQLException} is reached three ways and all three are walked. {@code getCause} is
     * the familiar one; {@code getNextException} is the JDBC-specific chain, easy to forget and just
     * as visible once anything iterates the exception; and suppressed exceptions are printed by
     * {@code printStackTrace} like any other.
     */
    private Throwable rewrite(Throwable original, String path, int depth) {
        if (depth >= MAX_CHAIN_LENGTH) {
            return redactedEntirely(original);
        }

        Throwable head = sanitizeMessage(original, path, false);
        boolean changed = head != original;

        // A Throwable whose cause is itself means "no cause"; treating it as one would recurse.
        Throwable cause = original.getCause() == original ? null : original.getCause();
        Throwable newCause = cause == null ? null : rewrite(cause, path + "/cause", depth + 1);
        changed |= newCause != cause;

        SQLException next = original instanceof SQLException sqle && sqle.getNextException() != original
                ? sqle.getNextException()
                : null;
        SQLException newNext = next == null ? null : (SQLException) rewrite(next, path + "/next", depth + 1);
        changed |= newNext != next;

        Throwable[] suppressed = original.getSuppressed();
        Throwable[] newSuppressed = new Throwable[suppressed.length];
        for (int i = 0; i < suppressed.length; i++) {
            newSuppressed[i] = rewrite(suppressed[i], path + "/suppressed", depth + 1);
            changed |= newSuppressed[i] != suppressed[i];
        }

        if (!changed) {
            return original;
        }

        // Something below needed rewriting even though the message here did not, and a cause cannot
        // be swapped into an existing exception — so a copy has to be made either way.
        Throwable result = head != original ? head : sanitizeMessage(original, path, true);
        if (newCause != null) {
            result.initCause(newCause);
        }
        if (newNext != null && result instanceof SQLException sqle) {
            sqle.setNextException(newNext);
        }
        for (Throwable each : newSuppressed) {
            result.addSuppressed(each);
        }
        return result;
    }

    /** One link, its chain ignored: the same instance when its own message was already clean. */
    private Throwable sanitizeMessage(Throwable original, String path, boolean force) {
        if (original instanceof SQLException exception) {
            if (POSTGRES_DRIVER_PRESENT && PostgresErrorSanitizer.handles(exception)) {
                return PostgresErrorSanitizer.sanitize(exception, text, path, force);
            }
            String message = text.primary(exception.getMessage(), path);
            if (!force && Objects.equals(message, exception.getMessage())) {
                return exception;
            }
            return standardFor(exception, message);
        }

        // Not a database exception — an I/O failure under the driver, say. Its text is scanned, and
        // if anything came out the object is replaced, because its type cannot be reconstructed
        // generically and keeping it would keep the raw message.
        String message = original.getMessage() == null ? null : engine.maskText(original.getMessage(), path);
        if (!force && Objects.equals(message, original.getMessage())) {
            return original;
        }
        return copyStackTrace(new SQLException(original.getClass().getName() + ": " + message), original);
    }

    /**
     * The standard JDBC subclass for a SQL state, so replacing the exception does not change how it
     * is caught or how Spring and Hibernate classify it. Both consult the SQL state, and the class
     * of the standard subclasses is derived from it: class 23 is an integrity constraint violation
     * whatever driver reported it.
     */
    private SQLException standardFor(SQLException original, String message) {
        String state = original.getSQLState();
        String sqlClass = state == null || state.length() < 2 ? "" : state.substring(0, 2);
        int code = original.getErrorCode();
        SQLException replacement =
                switch (sqlClass) {
                    case "23" -> new SQLIntegrityConstraintViolationException(message, state, code);
                    case "22" -> new SQLDataException(message, state, code);
                    case "42" -> new SQLSyntaxErrorException(message, state, code);
                    case "40" -> new SQLTransactionRollbackException(message, state, code);
                    case "08" -> new SQLNonTransientConnectionException(message, state, code);
                    case "0A" -> new SQLFeatureNotSupportedException(message, state, code);
                    default -> new SQLException(message, state, code);
                };
        return copyStackTrace(replacement, original);
    }

    /**
     * Everything gone but the SQL state. The last resort, for a chain too long to walk or a failure
     * inside the sanitiser: what has not been examined must not be passed on.
     */
    private SQLException redactedEntirely(Throwable original) {
        String state = original instanceof SQLException sqle ? sqle.getSQLState() : null;
        int code = original instanceof SQLException sqle ? sqle.getErrorCode() : 0;
        return copyStackTrace(new SQLException(engine.policy().redactionPlaceholder(), state, code), original);
    }

    private static <T extends Throwable> T copyStackTrace(T replacement, Throwable original) {
        // The stack trace is where the failure happened, not what it happened to. It is the half of
        // the exception worth keeping, and it is the half a naive rewrite throws away.
        replacement.setStackTrace(original.getStackTrace());
        return replacement;
    }

    private static boolean isPresent(String className) {
        try {
            Class.forName(className, false, SqlExceptionSanitizer.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
