package ch.raph.datamask.infrastructure.masker;

import ch.raph.datamask.api.MaskContext;

/** Shared text helpers for the built-in maskers. */
final class Masks {

    private Masks() {}

    static String repeat(char padding, int count) {
        return count <= 0 ? "" : String.valueOf(padding).repeat(count);
    }

    /** The value as text, or {@code null} for a null input. */
    static String text(Object value) {
        return value == null ? null : (value instanceof CharSequence cs ? cs.toString() : value.toString());
    }

    /** The configured fixed replacement if there is one, otherwise the policy placeholder. */
    static String placeholder(MaskContext context) {
        return context.replacement().isEmpty() ? context.redactionPlaceholder() : context.replacement();
    }

    /**
     * Hides everything but the last {@code keep} alphanumeric characters, leaving separators such as
     * spaces and dashes in place so the result keeps the shape of the original.
     */
    static String keepTrailing(String value, int keep, char padding) {
        if (keep <= 0) {
            return maskAllAlphanumeric(value, padding);
        }
        int alphanumeric = 0;
        for (int i = 0; i < value.length(); i++) {
            if (Character.isLetterOrDigit(value.charAt(i))) {
                alphanumeric++;
            }
        }
        if (alphanumeric <= keep) {
            // Revealing the whole value because it happens to be short is exactly the failure mode
            // to avoid: a four-character account suffix would pass through untouched.
            return maskAllAlphanumeric(value, padding);
        }

        int toHide = alphanumeric - keep;
        StringBuilder out = new StringBuilder(value.length());
        int seen = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                out.append(seen++ < toHide ? padding : c);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    static String maskAllAlphanumeric(String value, char padding) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            out.append(Character.isLetterOrDigit(c) ? padding : c);
        }
        return out.toString();
    }

    static String digitsOnly(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isDigit(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }
}
