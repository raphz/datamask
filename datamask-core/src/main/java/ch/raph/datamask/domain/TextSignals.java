package ch.raph.datamask.domain;

import org.jspecify.annotations.Nullable;

/**
 * What a piece of text contains, summarised in a single pass, so that a detector can answer "not in
 * here" without running its pattern over it.
 *
 * <p>This exists because of a measurement. Scanning a PII-free log line cost ten times what walking
 * the object graph around it cost: twelve regular expressions, each traversing the whole string, to
 * conclude that none of them matched. Nearly all of that work is avoidable, because nearly all of it
 * is provably hopeless before it starts — a pattern that needs an {@code @} cannot match text that
 * has none, and one that needs twelve digits cannot match text with three.
 *
 * <p>The summary is deliberately coarse: character presence, and a few counts. Anything finer would
 * start to cost what the pattern costs, which is the thing being avoided.
 *
 * <h2>Only ASCII is tracked</h2>
 *
 * Every built-in pattern is written in explicit ASCII classes ({@code [A-Z]}, {@code [0-9]},
 * {@code \d} without {@code UNICODE_CHARACTER_CLASS}), so an ASCII summary answers them exactly.
 * {@link #contains(char)} answers {@code true} for any non-ASCII character rather than {@code
 * false}: an unknown is a reason to run the detector, never a reason to skip it.
 *
 * @see PiiDetector#mightMatch(TextSignals)
 */
public final class TextSignals {

    private static final TextSignals EMPTY = new TextSignals(0L, 0L, 0, 0, 0, 0);

    private final long asciiLow;
    private final long asciiHigh;
    private final int length;
    private final int digits;
    private final int uppercaseLetters;
    private final int longestUppercaseRun;

    private TextSignals(
            long asciiLow, long asciiHigh, int length, int digits, int uppercaseLetters, int longestUppercaseRun) {
        this.asciiLow = asciiLow;
        this.asciiHigh = asciiHigh;
        this.length = length;
        this.digits = digits;
        this.uppercaseLetters = uppercaseLetters;
        this.longestUppercaseRun = longestUppercaseRun;
    }

    /** Summarises the text in one pass. Null or empty text yields a summary that contains nothing. */
    public static TextSignals of(@Nullable CharSequence text) {
        if (text == null || text.isEmpty()) {
            return EMPTY;
        }
        long low = 0L;
        long high = 0L;
        int digits = 0;
        int uppercase = 0;
        int longestRun = 0;
        int run = 0;
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (current < 64) {
                low |= 1L << current;
            } else if (current < 128) {
                high |= 1L << (current - 64);
            }
            if (current >= 'A' && current <= 'Z') {
                uppercase++;
                run++;
                if (run > longestRun) {
                    longestRun = run;
                }
                continue;
            }
            run = 0;
            if (current >= '0' && current <= '9') {
                digits++;
            }
        }
        return new TextSignals(low, high, text.length(), digits, uppercase, longestRun);
    }

    /**
     * Whether the character occurs in the text.
     *
     * <p>A non-ASCII character is not tracked, and is reported as present. That answer is the safe
     * one in both directions: it costs a detector run, where the opposite would silently skip a
     * detector whose pattern could have matched.
     */
    public boolean contains(char character) {
        if (character < 64) {
            return (asciiLow & (1L << character)) != 0;
        }
        if (character < 128) {
            return (asciiHigh & (1L << (character - 64))) != 0;
        }
        return true;
    }

    /** Whether every one of these characters occurs — for a pattern anchored on a literal. */
    public boolean containsAll(String characters) {
        for (int i = 0; i < characters.length(); i++) {
            if (!contains(characters.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** Whether at least one of these characters occurs — for a pattern with alternatives. */
    public boolean containsAny(String characters) {
        for (int i = 0; i < characters.length(); i++) {
            if (contains(characters.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /** The length of the text this summarises. */
    public int length() {
        return length;
    }

    /** How many ASCII digits occur, wherever they are — the bound a fixed-length identifier needs. */
    public int digits() {
        return digits;
    }

    /** How many ASCII capitals occur. */
    public int uppercaseLetters() {
        return uppercaseLetters;
    }

    /** The longest unbroken run of ASCII capitals — what a pattern like a BIC's is really asking for. */
    public int longestUppercaseRun() {
        return longestUppercaseRun;
    }

    @Override
    public String toString() {
        return "TextSignals[length=" + length + ", digits=" + digits + ", uppercase=" + uppercaseLetters
                + ", longestUppercaseRun=" + longestUppercaseRun + "]";
    }
}
