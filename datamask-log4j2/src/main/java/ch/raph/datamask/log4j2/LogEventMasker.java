package ch.raph.datamask.log4j2;

import ch.raph.datamask.application.DataMask;
import ch.raph.datamask.application.MaskingEngine;
import ch.raph.datamask.domain.MaskingObserver;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.impl.ContextDataFactory;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.MapMessage;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.message.ObjectMessage;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.logging.log4j.message.ReusableMessage;
import org.apache.logging.log4j.message.ReusableObjectMessage;
import org.apache.logging.log4j.message.SimpleMessage;
import org.apache.logging.log4j.util.IndexedReadOnlyStringMap;
import org.apache.logging.log4j.util.ReadOnlyStringMap;
import org.apache.logging.log4j.util.StringMap;

/**
 * Turns a log event into one that carries no PII.
 *
 * {@snippet :
 * LogEventMasker masker = new LogEventMasker(dataMask);
 * LogEvent safe = masker.mask(event);
 * }
 *
 * <p>{@link MaskingRewritePolicy} applies this to everything a {@code Rewrite} appender forwards. It is
 * public because the same job comes up elsewhere — a custom appender, a filter, a Spring
 * auto-configuration that already holds an engine.
 *
 * <h2>Everything an event can carry a value in</h2>
 *
 * <ul>
 *   <li><b>Parameters</b> are masked before the message is formatted, so a {@code @PII} field of an
 *       object passed to {@code logger.info("paid {}", customer)} is masked from its declaration
 *       rather than searched for afterwards in the rendered text.
 *   <li><b>The message</b> is scanned, which is what covers {@code logger.info("email " + email)} — a
 *       concatenated message has no parameter to mask and no annotation to read.
 *   <li><b>The thread context map</b> is scanned. It is the quietest leak of the four: attached to
 *       every line of a request, by code nobody re-reads.
 *   <li><b>The thrown exception</b> is replaced by a copy of the same type carrying a masked message,
 *       down the cause chain and the suppressed list. A unique-constraint violation quoting the
 *       offending row is a real, ordinary leak.
 *   <li><b>Map and object messages</b> — log4j2's structured logging — are masked value by value and
 *       keep their type, so a JSON layout still writes the same shape.
 * </ul>
 *
 * <p>The garbage-free variants of all of these — {@code ReusableParameterizedMessage}, a
 * {@code MutableLogEvent} standing in for its own message — are masked exactly like their immutable
 * counterparts. A masked copy is materialized outside the reusable lifecycle, since the reusable
 * instances are recycled the moment the logging call returns.
 *
 * <h2>The event is returned unchanged when it carried nothing</h2>
 *
 * That is the common case and it costs no allocation: the engine and the text sanitiser both return the
 * <em>same instance</em> when nothing was masked, so this class compares references and forwards the
 * original event. A PII-free log line pays only for the scan itself.
 *
 * <p>Thread-safe, and never throws: a masking failure is reported to the {@link MaskingObserver} and
 * yields an event with the message withheld. A logging call must not fail the business operation, and
 * must not fall back to the text it could not mask.
 */
public final class LogEventMasker {

    private final MaskingEngine engine;
    private final MaskingObserver observer;

    /**
     * Free text — the message, the context map, exception messages — can only be masked by scanning
     * it, so all of it follows {@code MaskingPolicy#scanUnannotatedText}. Declared PII in a parameter
     * is masked either way.
     */
    private final boolean scanText;

    public LogEventMasker(DataMask dataMask) {
        this(Objects.requireNonNull(dataMask, "dataMask").engine());
    }

    public LogEventMasker(MaskingEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.observer = engine.observer();
        this.scanText = engine.policy().scanUnannotatedText();
    }

    /** The same event when it carried no PII, a masked copy of it otherwise. */
    public LogEvent mask(LogEvent event) {
        try {
            return maskEvent(event);
        } catch (Throwable failure) {
            // Includes the MaskingException that FailureMode.THROW raises. Aborting a database write
            // on a bad value is right; aborting a log statement is not, so the message is withheld
            // instead — withheld, not passed through.
            observer.onFailure(event.getLoggerName(), failure);
            return withheld(event);
        }
    }

    /**
     * The masked text of an event's message, for a layout that renders it directly.
     *
     * <p>The message only: a pattern renders the exception and the context map through converters of
     * their own, which is why {@link MaskingRewritePolicy} is the complete answer and this is the
     * convenient one.
     */
    public String maskFormattedMessage(LogEvent event) {
        try {
            Message message = event.getMessage();
            if (message == null) {
                return "";
            }
            Throwable thrown = event.getThrown();
            return maskMessage(message, thrown, thrown, event.getLoggerName()).getFormattedMessage();
        } catch (Throwable failure) {
            observer.onFailure(event.getLoggerName(), failure);
            return engine.policy().redactionPlaceholder();
        }
    }

    private LogEvent maskEvent(LogEvent event) {
        String origin = event.getLoggerName();

        Throwable thrown = event.getThrown();
        Throwable maskedThrown = maskThrown(thrown, origin + ".exception", 0);

        Message message = event.getMessage();
        Message maskedMessage = maskMessage(message, thrown, maskedThrown, origin);

        StringMap maskedContext = maskContextData(event.getContextData(), origin);

        if (maskedMessage == message && maskedContext == null && maskedThrown == thrown) {
            return event;
        }

        Log4jLogEvent.Builder builder = new Log4jLogEvent.Builder(event);
        builder.setMessage(maskedMessage);
        if (maskedContext != null) {
            builder.setContextData(maskedContext);
        }
        if (maskedThrown != thrown) {
            // The thrown proxy an extended pattern and every JSON layout render is derived from this,
            // lazily and only if something asks for it. The copy builder does not carry the source
            // event's proxy across, so there is no stale one holding the raw text.
            builder.setThrown(maskedThrown);
        }
        return builder.build();
    }

    /**
     * What is logged when masking itself failed: the level, the logger, the thread and the timestamp,
     * and nothing whatsoever of the message.
     *
     * <p>The notice names no exception message, because the exception was raised while handling a value
     * and may well quote it.
     */
    private LogEvent withheld(LogEvent event) {
        return new Log4jLogEvent.Builder(event)
                .setMessage(new SimpleMessage(
                        engine.policy().redactionPlaceholder() + " [datamask withheld this message: masking failed]"))
                .setThrown(null)
                .setContextData(ContextDataFactory.emptyFrozenContextData())
                .build();
    }

    private Message maskMessage(Message message, Throwable thrown, Throwable maskedThrown, String origin) {
        if (message == null) {
            return null;
        }

        // A message can hold the throwable as well, and it is the same instance the event holds in
        // every case log4j2 produces. Masking it twice would build two copies of the same chain.
        Throwable messageThrown = message.getThrowable();
        Throwable maskedMessageThrown =
                messageThrown == thrown ? maskedThrown : maskThrown(messageThrown, origin + ".exception", 0);
        boolean throwableChanged = maskedMessageThrown != messageThrown;

        if (message instanceof ParameterizedMessage parameterized) {
            return maskParameterized(parameterized, maskedMessageThrown, throwableChanged, origin);
        }
        if (message instanceof ReusableObjectMessage reusableObject) {
            // The garbage-free counterpart of an ObjectMessage: same treatment, materialized as an
            // immutable one when it changed. Checked before the general reusable branch, whose
            // format-and-parameters view of this type would scan the object's text instead of masking
            // its declarations.
            Object parameter = reusableObject.getParameter();
            Object masked = maskArgument(parameter, origin + ".message");
            return masked == parameter ? message : new ObjectMessage(masked);
        }
        if (message instanceof ReusableMessage reusable) {
            return maskReusable(reusable, maskedMessageThrown, throwableChanged, origin);
        }
        if (message instanceof ObjectMessage object) {
            Object parameter = object.getParameter();
            Object masked = maskArgument(parameter, origin + ".message");
            return masked == parameter ? message : new ObjectMessage(masked);
        }
        if (message instanceof MapMessage<?, ?> map) {
            return maskMapMessage(map, origin);
        }
        return maskFormatted(message, throwableChanged, origin);
    }

    /**
     * The shape of {@code logger.info("paid {}", customer)}: the format is scanned, the parameters are
     * masked, and the two are put back together only if either changed.
     */
    private Message maskParameterized(
            ParameterizedMessage message, Throwable maskedThrown, boolean throwableChanged, String origin) {
        String format = message.getFormat();
        String maskedFormat = scan(format, origin + ".message");
        Object[] parameters = message.getParameters();
        Object[] maskedParameters = maskArguments(parameters, origin);

        if (maskedFormat == format && maskedParameters == parameters && !throwableChanged) {
            return message;
        }
        return new ParameterizedMessage(maskedFormat, maskedParameters, maskedThrown);
    }

    /**
     * The same shape running garbage-free: with {@code log4j2.enableThreadlocals} on — log4j2's default
     * outside a web app — the logger reuses a {@code ReusableParameterizedMessage}, and an appender may
     * see a {@code MutableLogEvent} standing in for its own message. Both expose the format and the
     * parameters, which get exactly the treatment {@link #maskParameterized} gives them.
     *
     * <p>A changed message is materialized as an immutable {@link ParameterizedMessage}: the reusable
     * instance is recycled the moment the logging call returns, so it must not be what carries the
     * masked event's text. An unchanged one stays as it is, keeping a clean line inside the
     * allocation-free lifecycle it was logged in.
     */
    @SuppressWarnings("deprecation") // Message.getFormat() is deprecated on the interface for being
    // meaningless on most messages; the reusable implementations re-declare it un-deprecated as the
    // pattern accessor, and the pattern is exactly what is needed here.
    private Message maskReusable(
            ReusableMessage message, Throwable maskedThrown, boolean throwableChanged, String origin) {
        String format = message.getFormat();
        if (format == null) {
            // A reusable simple message over a non-String CharSequence: its text is all it has.
            return maskFormatted(message, throwableChanged, origin);
        }
        String maskedFormat = scan(format, origin + ".message");
        Object[] parameters = detachedParameters(message);
        Object[] maskedParameters = maskArguments(parameters, origin);

        if (maskedFormat == format && maskedParameters == parameters && !throwableChanged) {
            return message;
        }
        return new ParameterizedMessage(maskedFormat, maskedParameters, maskedThrown);
    }

    /**
     * The parameters, bounded to {@code getParameterCount()}. Every implementation log4j2 ships already
     * trims; a custom reusable message may return its live internal buffer with trailing nulls, which
     * must be neither masked nor handed to the immutable message that outlives the recycling.
     */
    private static Object[] detachedParameters(ReusableMessage message) {
        Object[] parameters = message.getParameters();
        int count = message.getParameterCount();
        return parameters != null && parameters.length > count ? Arrays.copyOf(parameters, count) : parameters;
    }

    /**
     * Structured logging, which is a map of values and therefore a map of things to mask. The message
     * rebuilds itself through {@code newInstance}, so a {@code StringMapMessage} stays one and a JSON
     * layout writes the same shape it did before.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Message maskMapMessage(MapMessage<?, ?> message, String origin) {
        Map<String, Object> data = (Map<String, Object>) message.getData();
        Map<String, Object> masked = null;
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            Object value = entry.getValue();
            Object safe = maskArgument(value, origin + ".message." + entry.getKey());
            if (safe != value && masked == null) {
                masked = new LinkedHashMap<>(data);
            }
            if (masked != null) {
                masked.put(entry.getKey(), safe);
            }
        }
        if (masked == null) {
            return message;
        }
        try {
            return (Message) ((MapMessage) message).newInstance(masked);
        } catch (RuntimeException unsupported) {
            // A subclass that will not take these values back — masking may have produced a type its
            // own map does not hold. The text still goes out masked; only the structure is lost.
            observer.onFailure(origin + ".message", unsupported);
            return new SimpleMessage(scan(message.getFormattedMessage(), origin + ".message"));
        }
    }

    /**
     * Anything else — a simple, formatted or custom message — is masked as the text it renders to. A
     * message that also held a throwable is replaced outright rather than rebuilt, since dropping it
     * from the message is the fail-closed half of not being able to put a masked one back.
     */
    private Message maskFormatted(Message message, boolean throwableChanged, String origin) {
        String formatted = message.getFormattedMessage();
        String masked = scan(formatted, origin + ".message");
        if (masked == formatted && !throwableChanged) {
            return message;
        }
        return new SimpleMessage(masked);
    }

    private Object[] maskArguments(Object[] parameters, String origin) {
        if (parameters == null || parameters.length == 0) {
            return parameters;
        }
        Object[] masked = null;
        for (int i = 0; i < parameters.length; i++) {
            Object parameter = parameters[i];
            Object safe = maskArgument(parameter, origin + ".arg" + i);
            if (safe != parameter && masked == null) {
                masked = parameters.clone();
            }
            if (masked != null) {
                masked[i] = safe;
            }
        }
        return masked != null ? masked : parameters;
    }

    private Object maskArgument(Object argument, String path) {
        if (argument == null) {
            return null;
        }
        if (argument instanceof Throwable thrown) {
            // A parameter that is a throwable is rendered by calling toString() on it, so masking that
            // text is exactly what the line would have shown — and it avoids rebuilding an exception
            // for a value that is only being printed.
            String rendered = thrown.toString();
            String safe = scan(rendered, path);
            return safe == rendered ? argument : safe;
        }
        if (argument instanceof CharSequence text) {
            // Scanned here rather than through the engine, which would report the finding against a
            // path of its own choosing. Where in the event a value was found is the whole point of the
            // signal: "context.customer" names the code that set it, an empty path names nothing.
            String rendered = text.toString();
            String safe = scan(rendered, path);
            return safe == rendered ? argument : safe;
        }
        return engine.mask(argument);
    }

    /**
     * Scans the thread context map. Keys are left alone: they are names a developer wrote, not data,
     * and rewriting them would break every pattern and filter that refers to them.
     *
     * @return null when nothing changed, so the caller can forward the original event
     */
    private StringMap maskContextData(ReadOnlyStringMap contextData, String origin) {
        if (!scanText || contextData == null || contextData.isEmpty()) {
            return null;
        }
        if (contextData instanceof IndexedReadOnlyStringMap indexed) {
            StringMap masked = null;
            for (int i = 0; i < indexed.size(); i++) {
                String key = indexed.getKeyAt(i);
                Object value = indexed.getValueAt(i);
                Object safe = maskArgument(value, origin + ".context." + key);
                if (safe != value && masked == null) {
                    masked = ContextDataFactory.createContextData(contextData);
                }
                if (masked != null) {
                    masked.putValue(key, safe);
                }
            }
            return masked;
        }
        return maskContextDataByCopy(contextData, origin);
    }

    /** Everything log4j2 ships is indexed; this is the path a custom context data provider takes. */
    private StringMap maskContextDataByCopy(ReadOnlyStringMap contextData, String origin) {
        StringMap masked = null;
        for (Map.Entry<String, String> entry : contextData.toMap().entrySet()) {
            String value = entry.getValue();
            Object safe = maskArgument(value, origin + ".context." + entry.getKey());
            if (safe != value && masked == null) {
                masked = ContextDataFactory.createContextData(contextData);
            }
            if (masked != null) {
                masked.putValue(entry.getKey(), safe);
            }
        }
        return masked;
    }

    /**
     * Replaces an exception with one of the same type carrying a masked message.
     *
     * <p>The frames are kept: a class, a method and a line number identify code rather than a person,
     * and they are what makes the trace worth keeping. See {@link MaskedThrowables} for what happens
     * to a type that cannot be rebuilt.
     */
    private Throwable maskThrown(Throwable thrown, String path, int depth) {
        if (!scanText || thrown == null) {
            return thrown;
        }
        if (depth > engine.policy().maxDepth()) {
            // Bounded like the engine's own traversal. Cutting the chain short discloses nothing;
            // following an unbounded one would turn a log statement into an outage.
            observer.onDepthLimitExceeded(path);
            return null;
        }

        String message = thrown.getMessage();
        String maskedMessage = scan(message, path);
        Throwable cause = thrown.getCause();
        Throwable maskedCause = maskThrown(cause, path + ".cause", depth + 1);
        Throwable[] suppressed = thrown.getSuppressed();
        Throwable[] maskedSuppressed = maskSuppressed(suppressed, path, depth);

        if (maskedMessage == message && maskedCause == cause && maskedSuppressed == suppressed) {
            return thrown;
        }
        return MaskedThrowables.copyOf(thrown, maskedMessage, maskedCause, maskedSuppressed);
    }

    private Throwable[] maskSuppressed(Throwable[] suppressed, String path, int depth) {
        if (suppressed == null || suppressed.length == 0) {
            return suppressed;
        }
        Throwable[] masked = null;
        for (int i = 0; i < suppressed.length; i++) {
            Throwable safe = maskThrown(suppressed[i], path + ".suppressed" + i, depth + 1);
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
}
