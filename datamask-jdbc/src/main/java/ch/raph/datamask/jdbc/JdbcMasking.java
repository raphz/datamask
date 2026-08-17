package ch.raph.datamask.jdbc;

import ch.raph.datamask.api.MaskStrategy;
import ch.raph.datamask.api.PiiCategory;
import ch.raph.datamask.application.MaskingEngine;
import ch.raph.datamask.domain.MaskingObserver;
import ch.raph.datamask.domain.PiiDescriptor;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What the JDBC wrapper needs from the masking engine, in one place: exception sanitising, bind
 * parameter rendering, and the statement log itself.
 */
final class JdbcMasking {

    /**
     * A category of its own rather than the wrapper's class, so statement logging can be switched on
     * without turning on everything else this package might ever log. Set
     * {@code ch.raph.datamask.jdbc.statement} to DEBUG.
     */
    private static final Logger STATEMENTS = LoggerFactory.getLogger("ch.raph.datamask.jdbc.statement");

    private final MaskingEngine engine;
    private final MaskingObserver observer;
    private final SqlExceptionSanitizer exceptions;
    private final boolean wrapResultSets;

    JdbcMasking(MaskingEngine engine) {
        this(engine, true);
    }

    private JdbcMasking(MaskingEngine engine, boolean wrapResultSets) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.observer = engine.observer();
        this.exceptions = new SqlExceptionSanitizer(engine);
        this.wrapResultSets = wrapResultSets;
    }

    JdbcMasking withoutResultSetWrapping() {
        return new JdbcMasking(engine, false);
    }

    /** Whether result sets are proxied — see {@link MaskingDataSource#withoutResultSetWrapping()}. */
    boolean wrapsResultSets() {
        return wrapResultSets;
    }

    /** Sanitises a database exception on its way out of a JDBC call; anything else passes through. */
    Throwable translate(Throwable thrown) {
        return thrown instanceof SQLException exception ? sanitize(exception) : thrown;
    }

    SQLException sanitize(SQLException exception) {
        return exceptions.sanitize(exception);
    }

    /**
     * Whether parameters are worth recording at all.
     *
     * <p>Masking a bind parameter means running the detectors over it, and that is not free on a
     * path executed once per parameter per statement. Since the masked rendering exists only to be
     * logged, the whole mechanism is gated on the log level: with the category off, binding a
     * parameter costs nothing beyond the proxy call it already went through.
     */
    boolean recordsParameters() {
        return STATEMENTS.isDebugEnabled();
    }

    void logStatement(String sql, BoundParameters parameters) {
        if (!STATEMENTS.isDebugEnabled()) {
            return;
        }
        if (parameters.isEmpty()) {
            STATEMENTS.debug("{}", sql);
        } else {
            STATEMENTS.debug("{} {}", sql, parameters.describe());
        }
    }

    /**
     * A bind parameter as it may be written to a log.
     *
     * <p>Every parameter is masked, with no exception for values that look harmless. A bind
     * parameter is row data by definition, and there is no annotation to consult down here — the
     * column it is destined for is a name in a SQL string. Guessing which ones are safe is how a
     * logger ends up printing an account number, so nothing is printed verbatim.
     *
     * <p>What is left is still enough to read a query: which parameters were bound, in what order,
     * and of what type. Where a detector recognises the value outright the category's own masking is
     * used, so an IBAN reads as an IBAN and stays correlatable between log lines without being
     * disclosed.
     */
    String maskParameter(Object value, String path) {
        try {
            return render(value, path);
        } catch (RuntimeException e) {
            // A failure while preparing a log line must not fail the statement, and must not fall
            // back to the value it failed to mask.
            observer.onFailure(path, e);
            return placeholder();
        }
    }

    private String render(Object value, String path) {
        if (value == null) {
            return "null";
        }
        if (value instanceof CharSequence text) {
            return maskText(text, path);
        }
        if (value instanceof byte[] bytes) {
            // The length is structural; the bytes are not. A BLOB is exactly the kind of column a
            // scanned document ends up in.
            return "<byte[" + bytes.length + "]>";
        }
        // Numbers, booleans, dates and everything else are rendered as their type alone. A customer
        // id, a balance and a date of birth are all PII, and none of them has a partial form that
        // is safe by default.
        return "<" + value.getClass().getSimpleName() + ">";
    }

    private String maskText(CharSequence text, String path) {
        Optional<PiiCategory> category = engine.sanitizer().classify(text);
        if (category.isEmpty()) {
            // Unrecognised, therefore unknown, therefore redacted.
            observer.onMasked(path, PiiCategory.UNSPECIFIED, MaskStrategy.REDACT);
            return placeholder();
        }

        PiiCategory recognised = category.get();
        MaskStrategy strategy = recognised.defaultStrategy();
        if (strategy == MaskStrategy.AUTO || strategy == MaskStrategy.SCAN) {
            // Neither resolves to anything here: the value has already been classified, and SCAN
            // would re-enter the scanner on a value that is entirely one finding.
            strategy = MaskStrategy.REDACT;
        }
        observer.onUnannotatedPii(path, recognised, "bind-parameter");
        // Built through the factory and a wither rather than the canonical constructor: everything
        // but the strategy is the category's own default, and naming the one thing that differs is
        // what keeps this call site working when the record gains a component.
        PiiDescriptor descriptor = PiiDescriptor.of(recognised).withStrategy(strategy);
        Object masked = engine.maskDeclared(text.toString(), descriptor, String.class, path);
        return masked == null ? placeholder() : masked.toString();
    }

    private String placeholder() {
        return engine.policy().redactionPlaceholder();
    }
}
