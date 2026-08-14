package ch.raph.datamask.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.LoggerContextVO;
import java.util.List;
import java.util.Map;
import org.slf4j.Marker;
import org.slf4j.event.KeyValuePair;

/**
 * A masked view of an event: everything that can carry a value is replaced, everything else is the
 * original's.
 *
 * <p>A view rather than a copy because {@code LoggingEvent} cannot hold one. Its
 * {@code setThrowableProxy} takes the concrete {@code ThrowableProxy}, so a masked cause chain has
 * nowhere to go, and rebuilding the rest by hand would silently drop whatever logback adds to the
 * event next. Delegating means a new field on an event is carried through without this class knowing
 * about it.
 *
 * <p>Everything is computed before construction, so every getter is a field read. Appenders call them
 * from their own threads, and an encoder may call them more than once.
 */
final class MaskedLoggingEvent implements ILoggingEvent {

    private final ILoggingEvent delegate;
    private final String message;
    private final String formattedMessage;
    private final Object[] arguments;
    private final Map<String, String> mdc;
    private final List<KeyValuePair> keyValuePairs;
    private final IThrowableProxy throwableProxy;

    MaskedLoggingEvent(
            ILoggingEvent delegate,
            String message,
            String formattedMessage,
            Object[] arguments,
            Map<String, String> mdc,
            List<KeyValuePair> keyValuePairs,
            IThrowableProxy throwableProxy) {
        this.delegate = delegate;
        this.message = message;
        this.formattedMessage = formattedMessage;
        this.arguments = arguments;
        this.mdc = mdc;
        this.keyValuePairs = keyValuePairs;
        this.throwableProxy = throwableProxy;
    }

    /**
     * What is logged when masking itself failed: the level, the logger, the thread and the timestamp,
     * and nothing whatsoever of the message.
     *
     * <p>The notice names no exception message, because the exception was raised while handling a
     * value and may well quote it.
     */
    static ILoggingEvent withheld(ILoggingEvent event, String placeholder) {
        String notice = placeholder + " [datamask withheld this message: masking failed]";
        return new MaskedLoggingEvent(event, notice, notice, null, Map.of(), List.of(), null);
    }

    @Override
    public String getMessage() {
        return message;
    }

    @Override
    public String getFormattedMessage() {
        return formattedMessage;
    }

    @Override
    public Object[] getArgumentArray() {
        return arguments;
    }

    @Override
    public Map<String, String> getMDCPropertyMap() {
        return mdc;
    }

    @Override
    @Deprecated
    public Map<String, String> getMdc() {
        return mdc;
    }

    @Override
    public List<KeyValuePair> getKeyValuePairs() {
        return keyValuePairs;
    }

    @Override
    public IThrowableProxy getThrowableProxy() {
        return throwableProxy;
    }

    @Override
    public String getThreadName() {
        return delegate.getThreadName();
    }

    @Override
    public Level getLevel() {
        return delegate.getLevel();
    }

    @Override
    public String getLoggerName() {
        return delegate.getLoggerName();
    }

    @Override
    public LoggerContextVO getLoggerContextVO() {
        return delegate.getLoggerContextVO();
    }

    @Override
    public StackTraceElement[] getCallerData() {
        return delegate.getCallerData();
    }

    @Override
    public boolean hasCallerData() {
        return delegate.hasCallerData();
    }

    @Override
    public List<Marker> getMarkerList() {
        return delegate.getMarkerList();
    }

    @Override
    public long getTimeStamp() {
        return delegate.getTimeStamp();
    }

    @Override
    public int getNanoseconds() {
        return delegate.getNanoseconds();
    }

    @Override
    public long getSequenceNumber() {
        return delegate.getSequenceNumber();
    }

    @Override
    public void prepareForDeferredProcessing() {
        delegate.prepareForDeferredProcessing();
    }

    @Override
    public String toString() {
        return "[" + getLevel() + "] " + getLoggerName() + " - " + formattedMessage;
    }
}
