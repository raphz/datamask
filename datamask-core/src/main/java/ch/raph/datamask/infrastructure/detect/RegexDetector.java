package ch.raph.datamask.infrastructure.detect;

import ch.raph.datamask.api.PiiCategory;
import ch.raph.datamask.domain.PiiDetector;
import ch.raph.datamask.domain.PiiFinding;
import ch.raph.datamask.domain.TextSignals;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A detector driven by a pattern and, where the identifier has one, a check digit.
 *
 * <p>When the pattern declares a capturing group, only that group is reported. That is what lets a
 * pattern match {@code Authorization: Bearer abc123} for context while masking nothing but the
 * token.
 *
 * <p>{@link #gatedBy} attaches the cheap pre-check described on {@link PiiDetector#mightMatch}. It
 * is a separate call rather than another constructor parameter because it is the one part of a
 * detector that can be wrong without any test of the pattern noticing.
 */
public final class RegexDetector implements PiiDetector {

    private final String name;
    private final PiiCategory category;
    private final Pattern pattern;
    private final @Nullable Predicate<String> checksum;
    private final boolean requireChecksum;
    private final @Nullable Predicate<TextSignals> gate;

    public RegexDetector(String name, PiiCategory category, Pattern pattern) {
        this(name, category, pattern, null, false);
    }

    /**
     * @param checksum        confirms a match, or {@code null} when the identifier has no check digit
     * @param requireChecksum drop matches the checksum rejects. Set for identifiers that are just
     *                        runs of digits — without it, every order reference in a log would be
     *                        reported as a card number.
     */
    public RegexDetector(
            String name,
            PiiCategory category,
            Pattern pattern,
            @Nullable Predicate<String> checksum,
            boolean requireChecksum) {
        this(name, category, pattern, checksum, requireChecksum, null);
    }

    private RegexDetector(
            String name,
            PiiCategory category,
            Pattern pattern,
            @Nullable Predicate<String> checksum,
            boolean requireChecksum,
            @Nullable Predicate<TextSignals> gate) {
        this.name = Objects.requireNonNull(name, "name");
        this.category = Objects.requireNonNull(category, "category");
        this.pattern = Objects.requireNonNull(pattern, "pattern");
        this.checksum = checksum;
        this.requireChecksum = requireChecksum;
        this.gate = gate;
    }

    /**
     * The same detector, skipped outright for text whose summary fails this condition.
     *
     * <p>The condition must be <em>necessary</em> for the pattern to match: state what the pattern
     * cannot do without, never what it usually comes with. See {@link PiiDetector#mightMatch} for
     * what a wrong condition costs.
     */
    public RegexDetector gatedBy(Predicate<TextSignals> condition) {
        return new RegexDetector(
                name, category, pattern, checksum, requireChecksum, Objects.requireNonNull(condition, "condition"));
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean mightMatch(TextSignals signals) {
        return gate == null || gate.test(signals);
    }

    @Override
    public List<PiiFinding> detect(CharSequence text) {
        Matcher matcher = pattern.matcher(text);
        // Built on the first hit, not before it. Finding nothing is the overwhelmingly common
        // answer — every clean log line, every scanned field of every clean object — and on that
        // path this detector should cost the pattern match and not one object more.
        List<PiiFinding> findings = null;
        while (matcher.find()) {
            int group = matcher.groupCount() >= 1 ? 1 : 0;
            if (matcher.start(group) < 0) {
                continue;
            }
            String candidate = matcher.group(group);
            boolean confident = checksum == null || checksum.test(candidate);
            if (requireChecksum && !confident) {
                continue;
            }
            if (findings == null) {
                findings = new ArrayList<>();
            }
            findings.add(new PiiFinding(matcher.start(group), matcher.end(group), category, name, confident));
        }
        return findings == null ? List.of() : findings;
    }
}
