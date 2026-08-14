package ch.raph.datamask.domain;

import ch.raph.datamask.api.PiiCategory;

/**
 * A stretch of text a detector identified as personal data.
 *
 * @param start     index of the first character, inclusive
 * @param end       index after the last character, exclusive
 * @param category  what the detector believes it found
 * @param detector  which detector fired, for audit and for tuning false positives
 * @param confident whether the match was confirmed by a checksum rather than only by shape; an
 *                  IBAN that passes mod-97 or a card number that passes Luhn is confident, a
 *                  bare sixteen-digit run is not
 */
public record PiiFinding(int start, int end, PiiCategory category, String detector, boolean confident) {

    public PiiFinding {
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("invalid range [" + start + ", " + end + ")");
        }
    }

    public int length() {
        return end - start;
    }

    public boolean overlaps(PiiFinding other) {
        return start < other.end && other.start < end;
    }
}
