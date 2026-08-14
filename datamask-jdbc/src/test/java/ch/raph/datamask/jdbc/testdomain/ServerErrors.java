package ch.raph.datamask.jdbc.testdomain;

import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/**
 * Builds the exceptions the PostgreSQL driver builds, from the wire format the driver parses.
 *
 * <p>Not a stub. {@code ServerErrorMessage} takes the {@code \0}-delimited ErrorResponse exactly as
 * it arrives from the server, so what these methods hand back is the driver's own parsing of the
 * driver's own input — the same object graph a live server produces, without a live server. The
 * message texts below were captured from PostgreSQL 17 rather than written from memory.
 */
public final class ServerErrors {

    private ServerErrors() {}

    /** {@code ERROR: duplicate key value violates unique constraint "customer_email_key"} */
    public static PSQLException uniqueViolation(String duplicatedValue) {
        return new Builder()
                .severity("ERROR")
                .state("23505")
                .message("duplicate key value violates unique constraint \"customer_email_key\"")
                .detail("Key (email)=(" + duplicatedValue + ") already exists.")
                .table("customer")
                .constraint("customer_email_key")
                .schema("public")
                .build();
    }

    /** A foreign key violation, whose detail keeps prose after the offending value. */
    public static PSQLException foreignKeyViolation(String missingValue) {
        return new Builder()
                .severity("ERROR")
                .state("23503")
                .message("insert or update on table \"orders\" violates foreign key constraint \"orders_cust_fkey\"")
                .detail("Key (cust)=(" + missingValue + ") is not present in table \"customer\".")
                .table("orders")
                .constraint("orders_cust_fkey")
                .build();
    }

    /** A check-constraint violation, whose detail renders the whole row and never says {@code Key}. */
    public static PSQLException checkViolation(String row) {
        return new Builder()
                .severity("ERROR")
                .state("23514")
                .message("new row for relation \"customer\" violates check constraint \"chk\"")
                .detail("Failing row contains (" + row + ").")
                .table("customer")
                .constraint("chk")
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Assembles the {@code <fieldType><value>\0} pairs of a PostgreSQL ErrorResponse. */
    public static final class Builder {

        private final StringBuilder wire = new StringBuilder();

        public Builder severity(String value) {
            return field('S', value);
        }

        public Builder state(String value) {
            return field('C', value);
        }

        public Builder message(String value) {
            return field('M', value);
        }

        public Builder detail(String value) {
            return field('D', value);
        }

        public Builder hint(String value) {
            return field('H', value);
        }

        public Builder where(String value) {
            return field('W', value);
        }

        public Builder internalQuery(String value) {
            return field('q', value);
        }

        public Builder schema(String value) {
            return field('s', value);
        }

        public Builder table(String value) {
            return field('t', value);
        }

        public Builder column(String value) {
            return field('c', value);
        }

        public Builder constraint(String value) {
            return field('n', value);
        }

        public Builder field(char type, String value) {
            wire.append(type).append(value).append('\0');
            return this;
        }

        public PSQLException build() {
            return new PSQLException(new ServerErrorMessage(wire.toString()));
        }
    }
}
