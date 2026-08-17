package ch.raph.datamask.domain;

import org.jspecify.annotations.Nullable;

/**
 * Raised when masking cannot be completed and the policy is {@link FailureMode#THROW}.
 *
 * <p>The message deliberately identifies the path and the type but never the value, so that the
 * exception itself does not become the leak.
 *
 * <p>Built through {@link #atPath} and {@link #withoutPath} rather than constructors. The two
 * constructors this replaced took a path first and a message first respectively, so
 * {@code new MaskingException(a, b, null)} and {@code new MaskingException(a)} read alike at a call
 * site and meant different things — and the argument that would have been the path silently became
 * part of the message.
 */
public final class MaskingException extends RuntimeException {

    private final String path;

    private MaskingException(String path, String message, @Nullable Throwable cause) {
        super(message, cause);
        this.path = path;
    }

    /** A failure at a known point in the graph. The path is what an observer and an operator key on. */
    public static MaskingException atPath(String path, String message) {
        return atPath(path, message, null);
    }

    public static MaskingException atPath(String path, String message, @Nullable Throwable cause) {
        return new MaskingException(path, "masking failed at '" + path + "': " + message, cause);
    }

    /** A failure with no position in a graph — a missing algorithm, an unregistered masker. */
    public static MaskingException withoutPath(String message) {
        return new MaskingException("", message, null);
    }

    /** Where in the graph the failure happened, or the empty string when it had no position. */
    public String path() {
        return path;
    }
}
