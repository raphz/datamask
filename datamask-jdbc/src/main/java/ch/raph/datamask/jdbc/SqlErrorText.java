package ch.raph.datamask.jdbc;

import ch.raph.datamask.api.MaskStrategy;
import ch.raph.datamask.api.PiiCategory;
import ch.raph.datamask.application.MaskingEngine;
import ch.raph.datamask.domain.MaskingObserver;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes row values from the text of a database error, keeping the parts that explain the failure.
 *
 * <p>Everything here matches on <em>punctuation</em> rather than on words. A PostgreSQL error is
 * localised twice over — the server translates its own messages according to {@code lc_messages},
 * and the driver translates the {@code Detail:} and {@code Hint:} labels it wraps them in — so a
 * rule keyed on the English word {@code Key} would quietly stop redacting the moment someone
 * deploys against a German server. That is the failure mode this library exists to prevent, so the
 * structure {@code (columns)=(values)} is what gets matched: the parentheses and the equals sign
 * are emitted by the server unchanged in every locale.
 */
final class SqlErrorText {

    /**
     * The offending row as PostgreSQL renders it: {@code Key (email)=(john@x.com) already exists.}
     *
     * <p>The match is deliberately greedy to the <em>last</em> closing parenthesis. A value may
     * itself contain {@code )} — PostgreSQL does not escape it — and a lazy match would stop early
     * and leave the tail of the value in the message. Being greedy can swallow trailing prose such
     * as {@code is not present in table "customer"}, which costs a little diagnostic detail; being
     * lazy can leak. The primary message already names the constraint that failed.
     */
    private static final Pattern KEY_VALUES = Pattern.compile("\\)=\\(.*\\)", Pattern.DOTALL);

    /**
     * In {@code Where:} — PostgreSQL's {@code errcontext} — a double-quoted span is SQL statement
     * text, which carries literals. That is the opposite of the primary message, where a quoted
     * span is an identifier: a table, column or constraint name worth keeping.
     */
    private static final Pattern QUOTED = Pattern.compile("\"[^\"]*\"");

    /**
     * A single-quoted span. PostgreSQL is the driver that renders the offending row structurally;
     * every other one quotes the value in single quotes and says nothing else about it — MySQL's
     * {@code Duplicate entry 'john@x.com' for key 'customer.email_uq'}, MariaDB's copy of the same
     * message, H2's {@code ... VALUES ('john@x.com')}. A quoted value is therefore the only shape
     * there is to match, and the value is not required to be recognisable to a detector: a surname
     * or an internal reference is a row value like any other.
     */
    private static final Pattern SINGLE_QUOTED = Pattern.compile("'[^']*'", Pattern.DOTALL);

    /**
     * A bare SQL identifier, optionally qualified — {@code email}, {@code customer.email_uq}. The
     * leading character may not be a digit, which is what a plain unquoted SQL identifier requires
     * and what keeps a numeric id from being mistaken for one.
     */
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]{0,63}(?:\\.[A-Za-z_$][A-Za-z0-9_$]{0,63}){0,2}");

    private final MaskingEngine engine;
    private final MaskingObserver observer;

    SqlErrorText(MaskingEngine engine, MaskingObserver observer) {
        this.engine = engine;
        this.observer = observer;
    }

    /**
     * The primary message: kept, with the row structure masked and the remainder scanned.
     *
     * <p>It is not dropped when nothing matches, because it is the diagnostic core — the operation
     * that failed and the constraint that rejected it. PostgreSQL's own message style guide puts
     * data in {@code DETAIL} rather than here, and the driver agrees: the connection property
     * {@code logServerErrorDetail=false} reduces an error to exactly severity plus primary message
     * and calls the result non-sensitive.
     */
    String primary(String message, String path) {
        return scan(maskKeyValues(message, path), path);
    }

    /**
     * The primary message of an error that is <em>about a value</em> — SQL state class 22 (data
     * exception) or 23 (integrity constraint violation) — from a driver that is not PostgreSQL.
     *
     * <p>Same as {@link #primary}, plus the single-quoted spans. Without this the module's promise
     * holds only against PostgreSQL: {@code Duplicate entry 'john@x.com' for key 'customer.email'}
     * has no {@code (columns)=(values)} structure to match, and a value no detector recognises —
     * a surname, an internal reference — would reach the log verbatim.
     */
    String primaryWithQuotedValues(String message, String path) {
        return scan(maskQuotedValues(maskKeyValues(message, path), path), path);
    }

    /**
     * The detail: masked where the row structure is recognisable, and <b>dropped entirely</b>
     * otherwise.
     *
     * <p>This part exists to quote the offending row, so an unrecognised shape is far more likely
     * to be a row rendered some way this code has not seen than to be prose worth keeping —
     * {@code Failing row contains (3, bad@x.com, x).} is exactly that case, and dropping it is the
     * only safe reading. Returning {@code null} omits the part from the rebuilt error.
     */
    String detail(String detail, String path) {
        if (detail == null || detail.isEmpty()) {
            return detail;
        }
        String masked = maskKeyValues(detail, path);
        if (masked.equals(detail)) {
            observer.onMasked(path, PiiCategory.UNSPECIFIED, MaskStrategy.REDACT);
            return null;
        }
        return scan(masked, path);
    }

    /** The hint: generated advice about the schema, so it is scanned and kept. */
    String hint(String hint, String path) {
        return scan(maskKeyValues(hint, path), path);
    }

    /** The context stack: quoted statement text redacted, leaving the function and line intact. */
    String where(String where, String path) {
        if (where == null || where.isEmpty()) {
            return where;
        }
        Matcher matcher = QUOTED.matcher(where);
        if (!matcher.find()) {
            return scan(where, path);
        }
        observer.onMasked(path, PiiCategory.UNSPECIFIED, MaskStrategy.REDACT);
        return scan(matcher.reset().replaceAll(Matcher.quoteReplacement('"' + placeholder() + '"')), path);
    }

    private String maskKeyValues(String text, String path) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Matcher matcher = KEY_VALUES.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        // The category is genuinely unknown: this is a column value, and the error says nothing
        // about what kind. UNSPECIFIED with REDACT is the fail-closed reading of that.
        observer.onMasked(path, PiiCategory.UNSPECIFIED, MaskStrategy.REDACT);
        return matcher.reset().replaceAll(Matcher.quoteReplacement(")=(" + placeholder() + ")"));
    }

    /**
     * Redacts every single-quoted span, keeping at most one: a trailing span that is a bare SQL
     * identifier, which is how MySQL and MariaDB name the column or key that did the rejecting.
     */
    private String maskQuotedValues(String text, String path) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        List<MatchResult> spans = SINGLE_QUOTED.matcher(text).results().toList();
        if (spans.isEmpty()) {
            return text;
        }
        int kept = identifierToKeep(text, spans);
        if (kept == 0 && spans.size() == 1) {
            return text;
        }

        StringBuilder masked = new StringBuilder(text.length());
        int cursor = 0;
        for (int i = 0; i < spans.size(); i++) {
            if (i == kept) {
                continue;
            }
            MatchResult span = spans.get(i);
            masked.append(text, cursor, span.start())
                    .append('\'')
                    .append(placeholder())
                    .append('\'');
            cursor = span.end();
        }
        masked.append(text, cursor, text.length());
        // Same reading as the row structure: this is a column value and the error says nothing
        // about what kind, so UNSPECIFIED with REDACT is what the observer is told.
        observer.onMasked(path, PiiCategory.UNSPECIFIED, MaskStrategy.REDACT);
        return masked.toString();
    }

    /**
     * The index of the one span worth keeping, or {@code -1} when every one of them goes.
     *
     * <p>Only the <em>last</em> span is eligible, because these drivers say what was given before
     * they say what rejected it — {@code Duplicate entry 'x' for key 'k'}, {@code Incorrect integer
     * value: 'x' for column 'c' at row 1} — so anything before the last span is a value. And it is
     * kept only when it is a bare identifier: a name, not something with an {@code @}, a space or a
     * digit in front of it. What survives is therefore the column and constraint names, which are
     * what makes the error still worth reading.
     *
     * <p>A span inside a double-quoted region is never eligible. There the quoted text is SQL
     * rather than prose, as in {@link #where}, and H2 renders the offending value exactly that way:
     * {@code Value too long for column "EMAIL CHARACTER VARYING(5)": "'Mustermann' (10)"}.
     *
     * <p>The residual case is a value that is a single identifier-shaped token and the last thing
     * the message quotes. Content scanning runs over what is kept and catches it when a detector
     * recognises it; a bare surname in that position would survive, which is the price of keeping
     * the key name that every one of these messages puts in exactly the same place.
     */
    private static int identifierToKeep(String text, List<MatchResult> spans) {
        MatchResult last = spans.getLast();
        String content = text.substring(last.start() + 1, last.end() - 1);
        boolean insideSqlText =
                text.substring(0, last.start()).chars().filter(c -> c == '"').count() % 2 == 1;
        return !insideSqlText && IDENTIFIER.matcher(content).matches() ? spans.size() - 1 : -1;
    }

    /**
     * Content scanning, which is what catches a value the structural rules cannot see — an IBAN in
     * {@code invalid input syntax for type integer: "CH93..."}, say. This is also where
     * {@link MaskingObserver#onUnannotatedPii} fires, and a database error is the single most
     * valuable place for that signal: it means production data reached a log line.
     */
    private String scan(String text, String path) {
        return text == null || text.isEmpty() ? text : engine.maskText(text, path);
    }

    private String placeholder() {
        return engine.policy().redactionPlaceholder();
    }
}
