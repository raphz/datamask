package ch.raph.datamask.jdbc;

import ch.raph.datamask.api.MaskStrategy;
import ch.raph.datamask.api.PiiCategory;
import ch.raph.datamask.application.MaskingEngine;
import ch.raph.datamask.domain.MaskingObserver;
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
