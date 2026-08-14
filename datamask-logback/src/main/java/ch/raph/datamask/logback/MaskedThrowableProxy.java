package ch.raph.datamask.logback;

import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;

/**
 * An exception as it may be printed: the message masked, the frames untouched.
 *
 * <p>The message is the half that carries data. {@code Key (email)=(john@x.com) already exists} is a
 * constraint violation answering with the row that caused it, and it reaches the log, the stack trace
 * and whatever the logs are shipped to. The frames identify code rather than a person, and they are
 * what makes the trace worth keeping, so they are forwarded as they are.
 */
final class MaskedThrowableProxy implements IThrowableProxy {

    private final IThrowableProxy delegate;
    private final String message;
    private final String overridingMessage;
    private final IThrowableProxy cause;
    private final IThrowableProxy[] suppressed;

    MaskedThrowableProxy(
            IThrowableProxy delegate,
            String message,
            String overridingMessage,
            IThrowableProxy cause,
            IThrowableProxy[] suppressed) {
        this.delegate = delegate;
        this.message = message;
        this.overridingMessage = overridingMessage;
        this.cause = cause;
        this.suppressed = suppressed;
    }

    @Override
    public String getMessage() {
        return message;
    }

    @Override
    public String getOverridingMessage() {
        return overridingMessage;
    }

    @Override
    public IThrowableProxy getCause() {
        return cause;
    }

    @Override
    public IThrowableProxy[] getSuppressed() {
        return suppressed;
    }

    @Override
    public String getClassName() {
        return delegate.getClassName();
    }

    @Override
    public StackTraceElementProxy[] getStackTraceElementProxyArray() {
        return delegate.getStackTraceElementProxyArray();
    }

    @Override
    public int getCommonFrames() {
        return delegate.getCommonFrames();
    }

    @Override
    public boolean isCyclic() {
        return delegate.isCyclic();
    }
}
