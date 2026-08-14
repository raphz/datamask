package ch.raph.datamask.jdbc;

import java.sql.SQLException;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/**
 * Rebuilds a {@link PSQLException} with the row values taken out of it.
 *
 * <p>The obvious approach — regex the composed {@code getMessage()} string — is the wrong one twice
 * over: the labels in it are localised, and rewriting the string leaves
 * {@link PSQLException#getServerErrorMessage()} holding the raw value for anyone who asks. So this
 * works one level down instead. {@code ServerErrorMessage} parses the server's V3 error response
 * from a {@code \0}-delimited wire string of {@code <fieldType><value>} pairs, and that constructor
 * is public, so a sanitised error can be assembled in exactly the format the driver itself consumes.
 *
 * <p>What comes back is therefore a real {@code PSQLException}: {@code instanceof PSQLException}
 * still holds, {@code getSQLState()} is unchanged, {@code getServerErrorMessage()} is non-null and
 * carries the masked parts, and {@code getMessage()} is composed by the driver — which means the
 * {@code Detail:} label is still translated for the user's locale, for free.
 *
 * <p>This class is loaded only after {@link SqlExceptionSanitizer} has confirmed the driver is on
 * the classpath, which is what keeps the dependency optional.
 */
final class PostgresErrorSanitizer {

    /**
     * Field types from the PostgreSQL frontend/backend protocol's ErrorResponse message. Named here
     * rather than borrowed from the driver because they are private there.
     */
    private static final char SEVERITY = 'S';

    private static final char SQLSTATE = 'C';
    private static final char MESSAGE = 'M';
    private static final char DETAIL = 'D';
    private static final char HINT = 'H';
    private static final char POSITION = 'P';
    private static final char WHERE = 'W';
    private static final char SCHEMA = 's';
    private static final char TABLE = 't';
    private static final char COLUMN = 'c';
    private static final char DATATYPE = 'd';
    private static final char CONSTRAINT = 'n';
    private static final char FILE = 'F';
    private static final char LINE = 'L';
    private static final char ROUTINE = 'R';

    private PostgresErrorSanitizer() {}

    static boolean handles(SQLException exception) {
        return exception instanceof PSQLException psql && psql.getServerErrorMessage() != null;
    }

    /**
     * The sanitised equivalent, or the same instance when there was nothing to remove.
     *
     * <p>Returning the original unchanged matters for more than allocation: it means the wrapper
     * only ever alters an exception it actually had to alter, so the overwhelming majority of
     * database errors reach the application exactly as the driver produced them.
     *
     * <p>{@code force} asks for a rebuilt copy even when the parts came back clean, which is what
     * the caller needs when the message was fine but something further down the chain was not — an
     * exception's cause cannot be replaced in place.
     */
    static SQLException sanitize(SQLException exception, SqlErrorText text, String path, boolean force) {
        ServerErrorMessage server = ((PSQLException) exception).getServerErrorMessage();

        String message = text.primary(server.getMessage(), path + "/message");
        String detail = text.detail(server.getDetail(), path + "/detail");
        String hint = text.hint(server.getHint(), path + "/hint");
        String where = text.where(server.getWhere(), path + "/where");

        boolean unchanged = same(message, server.getMessage())
                && same(detail, server.getDetail())
                && same(hint, server.getHint())
                && same(where, server.getWhere())
                // The internal query is dropped rather than masked, so its presence is a change.
                && server.getInternalQuery() == null;
        if (unchanged && !force) {
            return exception;
        }

        StringBuilder wire = new StringBuilder();
        append(wire, SEVERITY, server.getSeverity());
        append(wire, SQLSTATE, server.getSQLState());
        append(wire, MESSAGE, message);
        append(wire, DETAIL, detail);
        append(wire, HINT, hint);
        append(wire, WHERE, where);

        // Identifiers and source locations, not data. Keeping them is what makes the sanitised
        // error still worth reading: the schema, table, column and constraint names survive.
        append(wire, SCHEMA, server.getSchema());
        append(wire, TABLE, server.getTable());
        append(wire, COLUMN, server.getColumn());
        append(wire, DATATYPE, server.getDatatype());
        append(wire, CONSTRAINT, server.getConstraint());
        append(wire, FILE, server.getFile());
        append(wire, ROUTINE, server.getRoutine());
        appendNumber(wire, LINE, server.getLine());

        // A character offset into a statement the error does not carry; discloses nothing.
        appendNumber(wire, POSITION, server.getPosition());

        // Internal Query and Internal Position are deliberately absent. The internal query is the
        // SQL text of a failing statement inside a function, literals and all, and there is no
        // structure in it to mask — so it goes, and the position that indexes into it goes with it.

        PSQLException sanitized = new PSQLException(new ServerErrorMessage(wire.toString()));
        sanitized.setStackTrace(exception.getStackTrace());
        return sanitized;
    }

    private static boolean same(String sanitized, String original) {
        return sanitized == null ? original == null : sanitized.equals(original);
    }

    private static void append(StringBuilder wire, char type, String value) {
        if (value != null && !value.isEmpty()) {
            wire.append(type).append(value).append('\0');
        }
    }

    private static void appendNumber(StringBuilder wire, char type, int value) {
        // Zero is how ServerErrorMessage reports an absent numeric field, so it is not written back.
        if (value != 0) {
            append(wire, type, Integer.toString(value));
        }
    }
}
