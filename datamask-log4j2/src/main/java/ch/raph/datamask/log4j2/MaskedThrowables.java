package ch.raph.datamask.log4j2;

import java.lang.reflect.Constructor;

/**
 * Builds the exception that replaces one whose message carried a value.
 *
 * <p>Log4j2 renders an exception through a {@code ThrowableProxy} built from a real {@code Throwable},
 * and both read the message from fields nothing can intercept. Masking an exception here therefore
 * means replacing it, and the replacement has to be of the <em>same type</em> or the log would name the
 * wrong exception — the one thing a reader trusts a stack trace for.
 *
 * <p>So the type is reconstructed: {@code (String, Throwable)} first, then {@code (String)}, with the
 * original's frames copied onto it. A type with neither constructor gets a stand-in that carries the
 * original class name in its <em>message</em>, so both {@code toString()} — what an ordinary
 * {@code %ex} pattern prints — and the message a JSON layout writes still name the type that was
 * thrown.
 *
 * <p>The message is the only place it can go. A {@code ThrowableProxy}, and every layout derived from
 * one, reads the class name off {@code getClass()}, which for a stand-in is the one thing about it that
 * cannot be the original's; the field is private and final and there is no way to set it. So a JSON
 * layout writing {@code exception.class} names this class for a type that could not be rebuilt, and
 * {@code exception.message} is where the original type is to be read. That is the residual limitation,
 * and it is documented in the module README.
 *
 * <p>What is lost is state the exception carried in fields of its own — a SQL state, an error code. It
 * is not printed by a layout, and losing it is the fail-closed direction.
 */
final class MaskedThrowables {

    private MaskedThrowables() {}

    static Throwable copyOf(Throwable original, String message, Throwable cause, Throwable[] suppressed) {
        Throwable copy = reconstruct(original.getClass(), message, cause);
        if (copy == null) {
            copy = new MaskedThrowable(original.getClass().getName(), message, cause);
        }
        copy.setStackTrace(original.getStackTrace());
        if (suppressed != null) {
            for (Throwable each : suppressed) {
                if (each != null && each != copy) {
                    try {
                        copy.addSuppressed(each);
                    } catch (RuntimeException refused) {
                        // A throwable constructed with suppression disabled. Dropping it loses a
                        // secondary trace and discloses nothing.
                    }
                }
            }
        }
        return copy;
    }

    private static Throwable reconstruct(Class<? extends Throwable> type, String message, Throwable cause) {
        Throwable copy = construct(type, new Class<?>[] {String.class, Throwable.class}, message, cause);
        if (copy != null) {
            return copy;
        }
        copy = construct(type, new Class<?>[] {String.class}, message);
        if (copy != null && cause != null) {
            try {
                copy.initCause(cause);
            } catch (IllegalStateException | IllegalArgumentException alreadySet) {
                // A constructor that set a cause of its own. The chain is shorter than it was, which
                // is a loss of detail rather than of safety.
            }
        }
        return copy;
    }

    private static Throwable construct(Class<? extends Throwable> type, Class<?>[] signature, Object... arguments) {
        try {
            Constructor<? extends Throwable> constructor = type.getConstructor(signature);
            return constructor.newInstance(arguments);
        } catch (ReflectiveOperationException | RuntimeException unusable) {
            // No such constructor, inaccessible, or one that validates its argument and refused the
            // masked message. The caller falls back to a stand-in.
            return null;
        }
    }

    /**
     * The stand-in for a type that cannot be rebuilt. The original class name goes into the message,
     * which is the only field of a {@code Throwable} a reader of the log can be given it through: an
     * {@code %ex} pattern prints {@code toString()}, but a JSON layout writes {@code exception.class}
     * from {@code getClass()} and {@code exception.message} from the message, and only the second of
     * those can be made to say what was thrown.
     */
    private static final class MaskedThrowable extends Throwable {

        MaskedThrowable(String className, String message, Throwable cause) {
            super(message == null ? className : className + ": " + message, cause);
        }

        @Override
        public String toString() {
            // Throwable.toString() would prefix this class's name to a message that already names the
            // original. Returning the message alone keeps %ex reading exactly as it did before.
            return getLocalizedMessage();
        }
    }
}
