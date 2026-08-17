package ch.raph.datamask.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import ch.raph.datamask.api.MaskStrategy;
import ch.raph.datamask.api.PiiCategory;
import ch.raph.datamask.application.DataMask;
import ch.raph.datamask.domain.FailureMode;
import ch.raph.datamask.domain.MaskingObserver;
import ch.raph.datamask.domain.MaskingPolicy;
import ch.raph.datamask.domain.PiiDetector;
import ch.raph.datamask.domain.PiiFinding;
import ch.raph.datamask.jdbc.testdomain.ServerErrors;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.BatchUpdateException;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;

@DisplayName("Sanitising a database exception")
class SqlExceptionSanitizerTest {

    private static final String EMAIL = "john@x.com";
    private static final String IBAN = "CH9300762011623852957";

    private final SqlExceptionSanitizer sanitizer = new SqlExceptionSanitizer(DataMask.withDefaults());

    /** Everything a rendered exception exposes, which is more than {@code getMessage()}. */
    private static String everythingVisible(Throwable exception) {
        StringWriter rendered = new StringWriter();
        exception.printStackTrace(new PrintWriter(rendered));
        return rendered + "\n" + exception + "\n" + exception.getMessage();
    }

    @Nested
    @DisplayName("that PostgreSQL answered with the offending row")
    class PostgresErrorDetail {

        @Test
        @DisplayName("takes the duplicated value out of a unique-constraint violation, which is the leak "
                + "this module exists for")
        void masksUniqueViolationDetail() {
            SQLException sanitized = sanitizer.sanitize(ServerErrors.uniqueViolation(EMAIL));

            assertThat(everythingVisible(sanitized)).doesNotContain(EMAIL);
            assertThat(sanitized.getMessage()).contains("Key (email)=(****) already exists.");
        }

        @Test
        @DisplayName("keeps the constraint, table and column names, so the error still says what failed")
        void keepsIdentifiers() {
            SQLException sanitized = sanitizer.sanitize(ServerErrors.uniqueViolation(EMAIL));

            assertThat(sanitized.getMessage())
                    .contains("duplicate key value violates unique constraint \"customer_email_key\"")
                    .contains("email");
            assertThat(((PSQLException) sanitized).getServerErrorMessage().getConstraint())
                    .isEqualTo("customer_email_key");
            assertThat(((PSQLException) sanitized).getServerErrorMessage().getTable())
                    .isEqualTo("customer");
        }

        @Test
        @DisplayName("empties the structured detail too, not only the composed message — that accessor is "
                + "where the raw value would otherwise still be sitting")
        void masksTheStructuredDetailAsWell() {
            SQLException sanitized = sanitizer.sanitize(ServerErrors.uniqueViolation(EMAIL));

            assertThat(((PSQLException) sanitized).getServerErrorMessage().getDetail())
                    .isNotNull()
                    .doesNotContain(EMAIL);
        }

        @Test
        @DisplayName("stays a PSQLException with its SQL state, so code catching the driver's own type and "
                + "Spring's exception translation both keep working")
        void preservesTypeAndState() {
            SQLException sanitized = sanitizer.sanitize(ServerErrors.uniqueViolation(EMAIL));

            assertThat((Throwable) sanitized).isInstanceOf(PSQLException.class);
            assertThat(sanitized.getSQLState()).isEqualTo("23505");
        }

        @Test
        @DisplayName("masks the value in a foreign-key detail")
        void masksForeignKeyDetail() {
            SQLException sanitized = sanitizer.sanitize(ServerErrors.foreignKeyViolation("4242"));

            assertThat(everythingVisible(sanitized)).doesNotContain("4242");
            assertThat(sanitized.getMessage()).contains("orders_cust_fkey");
        }

        @Test
        @DisplayName("drops a detail whose shape it does not recognise: `Failing row contains (...)` renders "
                + "the whole row and never says Key, so keeping any of it would be a guess")
        void dropsUnrecognisedDetail() {
            SQLException sanitized = sanitizer.sanitize(ServerErrors.checkViolation("3, " + EMAIL + ", x"));

            assertThat(everythingVisible(sanitized)).doesNotContain(EMAIL);
            assertThat(sanitized.getMessage()).doesNotContain("Failing row contains");
            // The primary message survives, so the failure is still identifiable.
            assertThat(sanitized.getMessage()).contains("violates check constraint \"chk\"");
        }

        @Test
        @DisplayName("keeps redacting when the value itself contains a closing parenthesis, which a lazy "
                + "match would stop short of")
        void masksValuesContainingParentheses() {
            PSQLException original = ServerErrors.builder()
                    .severity("ERROR")
                    .state("23505")
                    .message("duplicate key value violates unique constraint \"customer_name_key\"")
                    .detail("Key (name)=(Smith (Jr)) already exists.")
                    .build();

            assertThat(everythingVisible(sanitizer.sanitize(original))).doesNotContain("Smith");
        }

        @Test
        @DisplayName("masks a multi-column key, where the value list is not the first parenthesised group")
        void masksCompositeKeys() {
            PSQLException original = ServerErrors.builder()
                    .severity("ERROR")
                    .state("23505")
                    .message("duplicate key value violates unique constraint \"c_pkey\"")
                    .detail("Key (email, iban)=(" + EMAIL + ", " + IBAN + ") already exists.")
                    .build();

            SQLException sanitized = sanitizer.sanitize(original);

            assertThat(everythingVisible(sanitized)).doesNotContain(EMAIL).doesNotContain(IBAN);
            assertThat(sanitized.getMessage()).contains("Key (email, iban)=(****)");
        }

        @Test
        @DisplayName("drops the internal query, which is the SQL text of a statement inside a function and "
                + "has no structure to mask")
        void dropsInternalQuery() {
            PSQLException original = ServerErrors.builder()
                    .severity("ERROR")
                    .state("23505")
                    .message("duplicate key value violates unique constraint \"c\"")
                    .internalQuery("INSERT INTO customer VALUES ('" + EMAIL + "')")
                    .build();

            SQLException sanitized = sanitizer.sanitize(original);

            assertThat(everythingVisible(sanitized)).doesNotContain(EMAIL);
            assertThat(((PSQLException) sanitized).getServerErrorMessage().getInternalQuery())
                    .isNull();
        }

        @Test
        @DisplayName("redacts the quoted statement text in a context stack but keeps the function and line, "
                + "because there a quoted span is SQL rather than an identifier")
        void masksQuotedStatementTextInWhere() {
            PSQLException original = ServerErrors.builder()
                    .severity("ERROR")
                    .state("23505")
                    .message("duplicate key value violates unique constraint \"c\"")
                    .where("SQL statement \"INSERT INTO customer VALUES ('" + EMAIL + "')\"\n"
                            + "PL/pgSQL function add_customer(text) line 4 at SQL statement")
                    .build();

            SQLException sanitized = sanitizer.sanitize(original);

            assertThat(everythingVisible(sanitized)).doesNotContain(EMAIL);
            assertThat(sanitized.getMessage()).contains("PL/pgSQL function add_customer(text) line 4");
        }

        @Test
        @DisplayName("finds an IBAN in a primary message, which no structural rule would have matched")
        void scansThePrimaryMessage() {
            PSQLException original = ServerErrors.builder()
                    .severity("ERROR")
                    .state("22P02")
                    .message("invalid input syntax for type integer: \"" + IBAN + "\"")
                    .build();

            assertThat(everythingVisible(sanitizer.sanitize(original))).doesNotContain(IBAN);
        }

        @Test
        @DisplayName("returns the very same exception when it carried no row data, so an ordinary error "
                + "reaches the application exactly as the driver threw it")
        void returnsTheSameInstanceWhenNothingToRemove() {
            PSQLException original = ServerErrors.builder()
                    .severity("ERROR")
                    .state("42P01")
                    .message("relation \"customre\" does not exist")
                    .build();

            assertThat((Throwable) sanitizer.sanitize(original)).isSameAs(original);
        }
    }

    @Nested
    @DisplayName("from a driver that is not PostgreSQL")
    class OtherDrivers {

        @Test
        @DisplayName("masks the value in the message and maps SQL state 23 to the standard integrity "
                + "violation, so `catch (SQLIntegrityConstraintViolationException)` still fires")
        void mapsToStandardSubclass() {
            SQLException original = new SQLException(
                    "Unique index or primary key violation: \"PUBLIC.CUSTOMER(EMAIL) VALUES ('" + EMAIL + "')\"",
                    "23505",
                    23505);

            SQLException sanitized = sanitizer.sanitize(original);

            assertThat(everythingVisible(sanitized)).doesNotContain(EMAIL);
            assertThat((Throwable) sanitized).isInstanceOf(SQLIntegrityConstraintViolationException.class);
            assertThat(sanitized.getSQLState()).isEqualTo("23505");
            assertThat(sanitized.getErrorCode()).isEqualTo(23505);
        }

        @Test
        @DisplayName("leaves an error with nothing sensitive in it untouched")
        void passesCleanErrorsThrough() {
            SQLException original = new SQLException("connection closed", "08003");

            assertThat((Throwable) sanitizer.sanitize(original)).isSameAs(original);
        }

        @Test
        @DisplayName("takes the duplicated value out of MySQL's `Duplicate entry '...' for key '...'`, which "
                + "no detector would have recognised, and keeps the key name")
        void masksMySqlDuplicateEntry() {
            SQLException original = new SQLException(
                    "Duplicate entry 'Mustermann9910' for key 'customer.customer_email_key'", "23000", 1062);

            SQLException sanitized = sanitizer.sanitize(original);

            assertThat(everythingVisible(sanitized)).doesNotContain("Mustermann9910");
            assertThat(sanitized.getMessage())
                    .isEqualTo("Duplicate entry '****' for key 'customer.customer_email_key'");
            assertThat((Throwable) sanitized).isInstanceOf(SQLIntegrityConstraintViolationException.class);
        }

        @Test
        @DisplayName("does the same for MariaDB, whose message carries a connection number in front of it")
        void masksMariaDbDuplicateEntry() {
            SQLException original =
                    new SQLException("(conn=42) Duplicate entry 'Zurcher' for key 'PRIMARY'", "23000", 1062);

            SQLException sanitized = sanitizer.sanitize(original);

            assertThat(everythingVisible(sanitized)).doesNotContain("Zurcher");
            assertThat(sanitized.getMessage()).contains("(conn=42)").contains("for key 'PRIMARY'");
        }

        @Test
        @DisplayName("takes the value out of an H2 unique violation, where it is quoted inside the statement "
                + "text rather than after it")
        void masksH2UniqueViolation() {
            SQLException original = new SQLException(
                    "Unique index or primary key violation: \"PUBLIC.CONSTRAINT_INDEX_4 ON "
                            + "PUBLIC.CUSTOMER(EMAIL) VALUES ('Mustermann9910')\"; SQL statement:\n"
                            + "insert into customer (email) values (?) [23505-232]",
                    "23505",
                    23505);

            SQLException sanitized = sanitizer.sanitize(original);

            assertThat(everythingVisible(sanitized)).doesNotContain("Mustermann9910");
            assertThat(sanitized.getMessage())
                    .contains("Unique index or primary key violation")
                    .contains("CONSTRAINT_INDEX_4");
        }

        @Test
        @DisplayName("takes the value out of an H2 data exception too, where the last quoted span is the "
                + "value and keeping it would be the leak")
        void masksH2ValueTooLong() {
            SQLException original = new SQLException(
                    "Value too long for column \"EMAIL CHARACTER VARYING(8)\": \"'Mustermann9910' (14)\"; "
                            + "SQL statement:\ninsert into customer (email) values (?) [22001-232]",
                    "22001",
                    22001);

            SQLException sanitized = sanitizer.sanitize(original);

            assertThat(everythingVisible(sanitized)).doesNotContain("Mustermann9910");
            assertThat(sanitized.getMessage()).contains("EMAIL CHARACTER VARYING(8)");
        }

        @Test
        @DisplayName("keeps a column name that is all the message quotes, because an error naming nothing is "
                + "an error nobody can act on")
        void keepsAQuotedColumnName() {
            SQLException original = new SQLException("Data truncation: Data too long for column 'email' at row 1");

            assertThat((Throwable) sanitizer.sanitize(original)).isSameAs(original);
        }

        @Test
        @DisplayName("leaves quoted spans alone outside the two SQL state classes that are about a value, so "
                + "a syntax error still names the column it could not find")
        void redactsQuotedSpansOnlyWhereAValueIsExpected() {
            SQLException original = new SQLException("Unknown column 'emial' in 'field list'", "42S22", 1054);

            assertThat((Throwable) sanitizer.sanitize(original)).isSameAs(original);
        }
    }

    @Nested
    @DisplayName("from a batch")
    class Batches {

        private static final int[] COUNTS = {1, Statement.EXECUTE_FAILED, 1};

        @Test
        @DisplayName("keeps the update counts, which say which entry of the batch failed — a row position, "
                + "not row data — and which Hibernate and Spring both read")
        void keepsUpdateCounts() {
            BatchUpdateException original = new BatchUpdateException(
                    "Batch entry 0 insert into customer (email) values ('" + EMAIL + "') was aborted: "
                            + "ERROR: duplicate key value violates unique constraint \"customer_email_key\"\n"
                            + "  Detail: Key (email)=(" + EMAIL + ") already exists.",
                    "23505",
                    0,
                    COUNTS);

            SQLException sanitized = sanitizer.sanitize(original);

            assertThat(everythingVisible(sanitized)).doesNotContain(EMAIL);
            assertThat((Throwable) sanitized).isInstanceOf(BatchUpdateException.class);
            assertThat(((BatchUpdateException) sanitized).getUpdateCounts()).isEqualTo(COUNTS);
            assertThat(((BatchUpdateException) sanitized).getLargeUpdateCounts())
                    .containsExactly(1L, -3L, 1L);
            assertThat(sanitized.getSQLState()).isEqualTo("23505");
        }

        @Test
        @DisplayName("keeps them while replacing a cause that carried the value, which is the case where the "
                + "counts have to be handed to the constructor rather than set afterwards")
        void keepsUpdateCountsWhileSanitisingTheCause() {
            BatchUpdateException original =
                    new BatchUpdateException("batch failed", "23505", 0, COUNTS, ServerErrors.uniqueViolation(EMAIL));

            SQLException sanitized = sanitizer.sanitize(original);

            assertThat(everythingVisible(sanitized)).doesNotContain(EMAIL);
            assertThat(((BatchUpdateException) sanitized).getUpdateCounts()).isEqualTo(COUNTS);
            assertThat(sanitized.getCause()).isInstanceOf(PSQLException.class).isNotSameAs(original.getCause());
        }
    }

    @Nested
    @DisplayName("across the exception's chains")
    class Chains {

        @Test
        @DisplayName("sanitises a cause and does not keep the original as one, because a cause is printed "
                + "with the exception and would put the value straight back into the log")
        void sanitisesAndReplacesTheCause() {
            PSQLException cause = ServerErrors.uniqueViolation(EMAIL);
            SQLException original = new SQLException("could not execute statement", "23505", 0, cause);

            SQLException sanitized = sanitizer.sanitize(original);

            assertThat(everythingVisible(sanitized)).doesNotContain(EMAIL);
            assertThat(sanitized.getCause()).isNotSameAs(cause).isInstanceOf(PSQLException.class);
        }

        @Test
        @DisplayName("walks getNextException, the JDBC-specific chain that a rewrite keyed on getCause "
                + "alone would leave holding the value")
        void sanitisesTheNextException() {
            SQLException original = new SQLException("batch failed", "23505");
            original.setNextException(ServerErrors.uniqueViolation(EMAIL));

            SQLException sanitized = sanitizer.sanitize(original);

            assertThat(everythingVisible(sanitized)).doesNotContain(EMAIL);
            assertThat(everythingVisible(sanitized.getNextException())).doesNotContain(EMAIL);
        }

        @Test
        @DisplayName("walks suppressed exceptions, which printStackTrace renders like any other")
        void sanitisesSuppressedExceptions() {
            SQLException original = new SQLException("rollback failed", "40001");
            original.addSuppressed(ServerErrors.uniqueViolation(EMAIL));

            assertThat(everythingVisible(sanitizer.sanitize(original))).doesNotContain(EMAIL);
        }

        @Test
        @DisplayName("scans a cause that is not a database exception at all")
        void sanitisesNonSqlCauses() {
            SQLException original =
                    new SQLException("write failed", "08006", 0, new IOException("could not send " + IBAN));

            SQLException sanitized = sanitizer.sanitize(original);

            assertThat(everythingVisible(sanitized)).doesNotContain(IBAN);
            // The type it could not rebuild is recorded rather than silently lost.
            assertThat(sanitized.getCause().getMessage()).contains("java.io.IOException");
        }

        @Test
        @DisplayName("terminates on a chain that points back at itself instead of recursing forever")
        void survivesSelfReferentialChains() {
            SQLException original = new SQLException("failed for " + EMAIL, "23505");
            original.setNextException(original);

            assertThat(everythingVisible(sanitizer.sanitize(original))).doesNotContain(EMAIL);
        }

        @Test
        @DisplayName("redacts entirely rather than pass on a chain longer than it will walk")
        void redactsChainsTooLongToWalk() {
            SQLException head = new SQLException("head", "23505");
            SQLException tail = head;
            for (int i = 0; i < 40; i++) {
                SQLException next = ServerErrors.uniqueViolation(EMAIL);
                tail.setNextException(next);
                tail = next;
            }

            assertThat(everythingVisible(sanitizer.sanitize(head))).doesNotContain(EMAIL);
        }
    }

    @Nested
    @DisplayName("while failing")
    class FailClosed {

        @Test
        @DisplayName("redacts to nothing but the SQL state when the sanitiser itself throws, rather than "
                + "letting a bug here become the disclosure it exists to prevent")
        void redactsWhenSanitisingFails() {
            DataMask broken = DataMask.builder()
                    .detector(new PiiDetector() {
                        @Override
                        public String name() {
                            return "broken";
                        }

                        @Override
                        public List<PiiFinding> detect(CharSequence text) {
                            throw new IllegalStateException("detector is broken");
                        }
                    })
                    .build();

            SQLException sanitized = new SqlExceptionSanitizer(broken).sanitize(ServerErrors.uniqueViolation(EMAIL));

            assertThat(everythingVisible(sanitized)).doesNotContain(EMAIL);
            assertThat(sanitized.getSQLState()).isEqualTo("23505");
        }

        @Test
        @DisplayName("keeps the value out of the exception message even under FailureMode.PASS_THROUGH, "
                + "which is only ever a structural allowance")
        void neverPassesTheValueThrough() {
            DataMask permissive = DataMask.builder()
                    .policy(MaskingPolicy.strict().withFailureMode(FailureMode.PASS_THROUGH))
                    .build();

            SQLException sanitized =
                    new SqlExceptionSanitizer(permissive).sanitize(ServerErrors.uniqueViolation(EMAIL));

            assertThat(everythingVisible(sanitized)).doesNotContain(EMAIL);
        }

        @Test
        @DisplayName("keeps the stack trace, which says where the failure happened and never what it " + "happened to")
        void preservesTheStackTrace() {
            PSQLException original = ServerErrors.uniqueViolation(EMAIL);

            assertThat(sanitizer.sanitize(original).getStackTrace()).isEqualTo(original.getStackTrace());
        }
    }

    @Nested
    @DisplayName("reporting to the observer")
    class Observing {

        @Test
        @DisplayName("reports PII found in a database error as unannotated, which is the signal that "
                + "production data reached a log line")
        void reportsUnannotatedPii() {
            List<String> reported = new ArrayList<>();
            DataMask observed = DataMask.builder()
                    .observer(new MaskingObserver() {
                        @Override
                        public void onUnannotatedPii(String path, PiiCategory category, String detector) {
                            reported.add(path + " " + category);
                        }
                    })
                    .build();

            new SqlExceptionSanitizer(observed)
                    .sanitize(ServerErrors.builder()
                            .severity("ERROR")
                            .state("22P02")
                            .message("invalid input syntax for type integer: \"" + IBAN + "\"")
                            .build());

            assertThat(reported).anyMatch(entry -> entry.contains("IBAN"));
        }

        @Test
        @DisplayName("reports a redacted row value even though its category is unknowable from the error")
        void reportsStructuralRedaction() {
            List<String> reported = new ArrayList<>();
            DataMask observed = DataMask.builder()
                    .observer(new MaskingObserver() {
                        @Override
                        public void onMasked(String path, PiiCategory category, MaskStrategy strategy) {
                            reported.add(path + " " + category + " " + strategy);
                        }
                    })
                    .build();

            new SqlExceptionSanitizer(observed).sanitize(ServerErrors.uniqueViolation(EMAIL));

            assertThat(reported).anyMatch(entry -> entry.contains("UNSPECIFIED") && entry.contains("REDACT"));
        }
    }
}
