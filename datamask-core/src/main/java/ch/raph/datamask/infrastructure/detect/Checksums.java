package ch.raph.datamask.infrastructure.detect;

/**
 * Check-digit algorithms for the identifiers this library recognises.
 *
 * <p>Detection without a checksum is a false-positive generator: every order reference and every
 * correlation id is a long run of digits. Validating the check digit is what makes it safe to
 * enable content scanning in production.
 */
public final class Checksums {

    private Checksums() {}

    /** Luhn (ISO/IEC 7812-1), the check digit on payment card numbers. */
    public static boolean luhn(CharSequence digits) {
        int length = digits.length();
        if (length < 12 || length > 19) {
            return false;
        }
        int sum = 0;
        boolean doubling = false;
        for (int i = length - 1; i >= 0; i--) {
            char c = digits.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
            int digit = c - '0';
            if (doubling) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubling = !doubling;
        }
        return sum % 10 == 0;
    }

    /** ISO 13616 IBAN validation: rearrange, transliterate, and check that the value mod 97 is 1. */
    public static boolean iban(CharSequence candidate) {
        String compact = compact(candidate);
        if (compact.length() < 15 || compact.length() > 34) {
            return false;
        }
        if (!Character.isLetter(compact.charAt(0))
                || !Character.isLetter(compact.charAt(1))
                || !Character.isDigit(compact.charAt(2))
                || !Character.isDigit(compact.charAt(3))) {
            return false;
        }

        String rearranged = compact.substring(4) + compact.substring(0, 4);
        // The number is far too large for a long, so the modulus is taken incrementally.
        int remainder = 0;
        for (int i = 0; i < rearranged.length(); i++) {
            char c = rearranged.charAt(i);
            int value;
            if (Character.isDigit(c)) {
                value = c - '0';
                remainder = (remainder * 10 + value) % 97;
            } else if (Character.isLetter(c)) {
                value = Character.toUpperCase(c) - 'A' + 10;
                remainder = (remainder * 100 + value) % 97;
            } else {
                return false;
            }
        }
        return remainder == 1;
    }

    /**
     * Swiss AVS/AHV social insurance number: thirteen digits beginning 756, with an EAN-13 check
     * digit. This is the single most sensitive identifier in a Swiss banking data set.
     */
    public static boolean swissAhv(CharSequence candidate) {
        String compact = digitsOnly(candidate);
        if (compact.length() != 13 || !compact.startsWith("756")) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            int digit = compact.charAt(i) - '0';
            sum += (i % 2 == 0) ? digit : digit * 3;
        }
        int check = (10 - (sum % 10)) % 10;
        return check == compact.charAt(12) - '0';
    }

    public static String compact(CharSequence value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                out.append(Character.toUpperCase(c));
            }
        }
        return out.toString();
    }

    public static String digitsOnly(CharSequence value) {
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
