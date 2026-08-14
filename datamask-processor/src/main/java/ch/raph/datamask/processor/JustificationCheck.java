package ch.raph.datamask.processor;

import ch.raph.datamask.api.NoMask;
import java.util.Locale;
import java.util.Set;

/**
 * Checks that a {@code @NoMask} exemption carries a justification worth reading.
 *
 * <p>{@code @NoMask} is the one annotation in the library that makes output <em>less</em> masked, so
 * the justification is the thing a reviewer is meant to react to. The compiler already demands the
 * attribute be present; what it cannot demand is that it says anything, and an exemption justified
 * with {@code ""} or {@code "TODO"} is indistinguishable from one nobody thought about.
 */
final class JustificationCheck {

    /**
     * Deliberately short. Anything longer starts rejecting real justifications, and a check that
     * cries wolf gets switched off — which costs more than the placeholders it caught.
     */
    private static final Set<String> PLACEHOLDERS =
            Set.of("todo", "tbd", "fixme", "xxx", "n/a", "na", "none", "no", "-", "?", "because", "reasons", "test");

    private final Reporter reporter;

    JustificationCheck(Reporter reporter) {
        this.reporter = reporter;
    }

    void check(AnnotationSite site, NoMask annotation) {
        String justification = annotation.justification();
        if (justification.isBlank()) {
            reporter.error(
                    site,
                    "justification",
                    "@NoMask on " + site.path() + ": the justification is blank. State why this value is safe "
                            + "to disclose — it is what a reviewer and a compliance report are reading.");
            return;
        }
        if (PLACEHOLDERS.contains(normalise(justification))) {
            reporter.warning(
                    site,
                    "justification",
                    "@NoMask on " + site.path() + ": the justification reads as a placeholder rather than a "
                            + "reason. State why this value is safe to disclose.");
        }
    }

    private static String normalise(String justification) {
        return justification.strip().toLowerCase(Locale.ROOT).replaceAll("[.!\\s]+$", "");
    }
}
