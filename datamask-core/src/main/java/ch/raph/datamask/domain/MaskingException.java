package ch.raph.datamask.domain;

/**
 * Raised when masking cannot be completed and the policy is {@link FailureMode#THROW}.
 *
 * <p>The message deliberately identifies the path and the type but never the value, so that the
 * exception itself does not become the leak.
 */
public class MaskingException extends RuntimeException {

    private final String path;

    public MaskingException(String path, String message, Throwable cause) {
        super("masking failed at '" + path + "': " + message, cause);
        this.path = path;
    }

    public MaskingException(String message) {
        super(message);
        this.path = "";
    }

    public String path() {
        return path;
    }
}
