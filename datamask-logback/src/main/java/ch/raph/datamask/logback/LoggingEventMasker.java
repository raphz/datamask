package ch.raph.datamask.logback;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.raph.datamask.application.DataMask;
import ch.raph.datamask.application.MaskingEngine;
import ch.raph.datamask.domain.MaskingObserver;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.event.KeyValuePair;
import org.slf4j.helpers.MessageFormatter;

/**
 * Turns a logging event into one that carries no PII.
 *
 * {@snippet :
 * LoggingEventMasker masker = new LoggingEventMasker(dataMask);
 * ILoggingEvent safe = masker.mask(event);
 * }
 *
 * <p>{@link MaskingAppender} applies this to everything on its way to the appenders underneath it.
 * It is public because the same job comes up elsewhere — a custom appender, an encoder, a Spring
 * auto-configuration that already holds an engine.
 *
 * <h2>Everything an event can carry a value in</h2>
 *
 * <ul>
 *   <li><b>Arguments</b> are masked before the message is formatted, so a {@code @PII} field of an
 *       object passed to {@code log.info("paid {}", customer)} is masked rather than searched for
 *       afterwards in the rendered text.
 *   <li><b>The message</b> is scanned, which is what covers {@code log.info("email " + email)} — a
 *       concatenated message has no arguments to mask and no annotation to read.
 *   <li><b>MDC values</b> are scanned. A request-scoped MDC is the quietest leak of the four: it is
 *       attached to every line, and nobody re-reads the code that populates it.
 *   <li><b>Exception messages</b> are scanned, down the cause chain and the suppressed list. A
 *       unique-constraint violation quoting the offending row is a real, ordinary leak.
 *   <li><b>Key-value pairs</b> from the fluent API ({@code atInfo().addKeyValue(...)}) are masked
 *       like arguments, since that is what they are.
 * </ul>
 *
 * <h2>The event is returned unchanged when it carried nothing</h2>
 *
 * That is the common case and it costs no allocation: the engine and the text sanitiser both return
 * the <em>same instance</em> when nothing was masked, so this class compares references and forwards
 * the original event. A PII-free log line passes through with only the scan itself to pay for.
 *
 * <p>Thread-safe, and never throws: a masking failure is reported to the {@link MaskingObserver} and
 * yields an event with the message withheld. A logging call must not fail the business operation, and
 * must not fall back to the text it could not mask.
 */
public final class LoggingEventMasker {

    private final MaskingEngine engine;
    private final MaskingObserver observer;

    /**
     * Free text — the message, MDC values, exception messages — can only be masked by scanning it,
     * so all of it follows {@code MaskingPolicy#scanUnannotatedText}. Declared PII in an argument is
     * masked either way.
     */
    private final boolean scanText;

    public LoggingEventMasker(DataMask dataMask) {
        this(Objects.requireNonNull(dataMask, "dataMask").engine());
    }

    public LoggingEventMasker(MaskingEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.observer = engine.observer();
        this.scanText = engine.policy().scanUnannotatedText();
    }

    /** The same event when it carried no PII, a masked view of it otherwise. */
    public ILoggingEvent mask(ILoggingEvent event) {
        try {
            return maskEvent(event);
        } catch (Throwable failure) {
            // Includes the MaskingException that FailureMode.THROW raises. Aborting a database
            // write on a bad value is right; aborting a log statement is not, so the event is
            // withheld instead of thrown — and withheld, not passed through.
            observer.onFailure(event.getLoggerName(), failure);
            return MaskedLoggingEvent.withheld(event, engine.policy().redactionPlaceholder());
        }
    }

    private ILoggingEvent maskEvent(ILoggingEvent event) {
        String origin = event.getLoggerName();

        Object[] arguments = event.getArgumentArray();
        Object[] maskedArguments = maskArguments(arguments, origin);

        String message = event.getMessage();
        String maskedMessage = scan(message, origin + ".message");

        Map<String, String> mdc = event.getMDCPropertyMap();
        Map<String, String> maskedMdc = maskMdc(mdc, origin);

        List<KeyValuePair> keyValuePairs = event.getKeyValuePairs();
        List<KeyValuePair> maskedKeyValuePairs = maskKeyValuePairs(keyValuePairs, origin);

        IThrowableProxy throwable = event.getThrowableProxy();
        IThrowableProxy maskedThrowable = maskThrowable(throwable, origin + ".exception", 0);

        boolean bodyChanged = maskedArguments != arguments || maskedMessage != message;
        if (!bodyChanged
                && maskedMdc == mdc
                && maskedKeyValuePairs == keyValuePairs
                && maskedThrowable == throwable) {
            return event;
        }

        // Formatting is left to the event itself when neither half of the message changed, so a line
        // masked only in its MDC is not formatted twice.
        String formattedMessage = bodyChanged ? format(maskedMessage, maskedArguments) : event.getFormattedMessage();
        return new MaskedLoggingEvent(
                event, maskedMessage, formattedMessage, maskedArguments, maskedMdc, maskedKeyValuePairs,
                maskedThrowable);
    }

    /**
     * Masks the argument array before the message is formatted.
     *
     * <p>Doing it here rather than on the rendered text is the difference between reading a
     * declaration and guessing: {@code log.info("paid {}", customer)} masks the customer's IBAN
     * because the field says what it is, and the rendered line never contains it in the first place.
     */
    private Object[] maskArguments(Object[] arguments, String origin) {
        if (arguments == null || arguments.length == 0) {
            return arguments;
        }
        Object[] masked = null;
        for (int i = 0; i < arguments.length; i++) {
            Object argument = arguments[i];
            Object safe = maskArgument(argument, origin + ".arg" + i);
            if (safe != argument && masked == null) {
                masked = arguments.clone();
            }
            if (masked != null) {
                masked[i] = safe;
            }
        }
        return masked != null ? masked : arguments;
    }

    private Object maskArgument(Object argument, String path) {
        if (argument == null) {
            return null;
        }
        if (argument instanceof Throwable thrown) {
            // A Throwable has no masked copy: the engine would fail to rebuild it and drop it, or
            // throw under FailureMode.THROW. What a formatter does with it is call toString(), so
            // masking that text is both safe and exactly what the line would have shown.
            String rendered = thrown.toString();
            String safe = scan(rendered, path);
            return safe == rendered ? argument : safe;
        }
        return engine.mask(argument);
    }

    /**
     * Scans MDC values. Keys are left alone: they are names a developer wrote, not data, and
     * rewriting them would break every pattern and filter that refers to them.
     */
    private Map<String, String> maskMdc(Map<String, String> mdc, String origin) {
        if (!scanText || mdc == null || mdc.isEmpty()) {
            return mdc;
        }
        Map<String, String> masked = null;
        for (Map.Entry<String, String> entry : mdc.entrySet()) {
            String value = entry.getValue();
            String safe = scan(value, origin + ".mdc." + entry.getKey());
            if (safe != value && masked == null) {
                masked = new LinkedHashMap<>(mdc);
            }
            if (masked != null) {
                masked.put(entry.getKey(), safe);
            }
        }
        return masked != null ? masked : mdc;
    }

    /** Structured arguments are arguments, and are masked as such. */
    private List<KeyValuePair> maskKeyValuePairs(List<KeyValuePair> pairs, String origin) {
        if (pairs == null || pairs.isEmpty()) {
            return pairs;
        }
        List<KeyValuePair> masked = null;
        for (int i = 0; i < pairs.size(); i++) {
            KeyValuePair pair = pairs.get(i);
            Object safe = maskArgument(pair.value, origin + ".kv." + pair.key);
            if (safe != pair.value && masked == null) {
                masked = new ArrayList<>(pairs);
            }
            if (masked != null) {
                masked.set(i, new KeyValuePair(pair.key, safe));
            }
        }
        return masked != null ? masked : pairs;
    }

    /**
     * Scans the message of an exception, its cause chain and everything suppressed on it.
     *
     * <p>The frames are left untouched: a class, a method and a line number identify code, not a
     * person, and they are what makes the trace worth keeping.
     */
    private IThrowableProxy maskThrowable(IThrowableProxy throwable, String path, int depth) {
        if (!scanText || throwable == null) {
            return throwable;
        }
        if (depth > engine.policy().maxDepth()) {
            // Bounded like the engine's own traversal. Cutting the chain short discloses nothing;
            // following an unbounded one would turn a log statement into an outage.
            observer.onDepthLimitExceeded(path);
            return null;
        }

        String message = throwable.getMessage();
        String maskedMessage = scan(message, path);
        String overriding = throwable.getOverridingMessage();
        String maskedOverriding = scan(overriding, path);

        IThrowableProxy cause = throwable.getCause();
        IThrowableProxy maskedCause = maskThrowable(cause, path + ".cause", depth + 1);

        IThrowableProxy[] suppressed = throwable.getSuppressed();
        IThrowableProxy[] maskedSuppressed = maskSuppressed(suppressed, path, depth);

        if (maskedMessage == message
                && maskedOverriding == overriding
                && maskedCause == cause
                && maskedSuppressed == suppressed) {
            return throwable;
        }
        return new MaskedThrowableProxy(throwable, maskedMessage, maskedOverriding, maskedCause, maskedSuppressed);
    }

    private IThrowableProxy[] maskSuppressed(IThrowableProxy[] suppressed, String path, int depth) {
        if (suppressed == null || suppressed.length == 0) {
            return suppressed;
        }
        IThrowableProxy[] masked = null;
        for (int i = 0; i < suppressed.length; i++) {
            IThrowableProxy safe = maskThrowable(suppressed[i], path + ".suppressed" + i, depth + 1);
            if (safe != suppressed[i] && masked == null) {
                masked = suppressed.clone();
            }
            if (masked != null) {
                masked[i] = safe;
            }
        }
        return masked != null ? masked : suppressed;
    }

    /** Returns the same instance when nothing matched, which is what keeps a clean line free. */
    private String scan(String text, String path) {
        if (!scanText || text == null || text.isEmpty()) {
            return text;
        }
        return engine.maskText(text, path);
    }

    private static String format(String message, Object[] arguments) {
        if (message == null || arguments == null || arguments.length == 0) {
            return message;
        }
        return MessageFormatter.basicArrayFormat(message, arguments);
    }
}
