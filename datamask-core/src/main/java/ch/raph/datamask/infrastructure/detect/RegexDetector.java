package ch.raph.datamask.infrastructure.detect;

import ch.raph.datamask.api.PiiCategory;
import ch.raph.datamask.domain.PiiDetector;
import ch.raph.datamask.domain.PiiFinding;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A detector driven by a pattern and, where the identifier has one, a check digit.
 *
 * <p>When the pattern declares a capturing group, only that group is reported. That is what lets a
 * pattern match {@code Authorization: Bearer abc123} for context while masking nothing but the
 * token.
 */
public final class RegexDetector implements PiiDetector {

    private final String name;
    private final PiiCategory category;
    private final Pattern pattern;
    private final Predicate<String> checksum;
    private final boolean requireChecksum;

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
            String name, PiiCategory category, Pattern pattern, Predicate<String> checksum, boolean requireChecksum) {
        this.name = Objects.requireNonNull(name, "name");
        this.category = Objects.requireNonNull(category, "category");
        this.pattern = Objects.requireNonNull(pattern, "pattern");
        this.checksum = checksum;
        this.requireChecksum = requireChecksum;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public List<PiiFinding> detect(CharSequence text) {
        Matcher matcher = pattern.matcher(text);
        List<PiiFinding> findings = new ArrayList<>();
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
            findings.add(new PiiFinding(matcher.start(group), matcher.end(group), category, name, confident));
        }
        return findings;
    }
}
