package ch.raph.datamask.processor;

import ch.raph.datamask.api.PII;
import ch.raph.datamask.api.PiiCategory;

/**
 * Checks that nothing asks to keep part of a category that is never partially revealed.
 *
 * <p>{@code PiiDescriptor} forces {@code keep} to zero for card verification values, credentials,
 * biometrics and card expiry, so the annotation is corrected in silence and the code goes on saying
 * something that is not true. A reviewer reading {@code keep = 3} on a CVV has to know the runtime
 * overrides it to conclude the code is safe; a warning here is what makes the source agree with the
 * behaviour.
 *
 * <p>A warning rather than an error on purpose: the value is already fully masked, so this is a
 * misleading declaration and not a leak.
 */
final class PartialRevealCheck {

    private final Reporter reporter;

    PartialRevealCheck(Reporter reporter) {
        this.reporter = reporter;
    }

    void check(AnnotationSite site, PII annotation) {
        PiiCategory category = annotation.category();
        if (!category.neverPartiallyReveal() || annotation.keep() <= 0) {
            return;
        }
        reporter.warning(
                site,
                "keep",
                "@PII on " + site.path() + ": keep = " + annotation.keep() + " is ignored for category "
                        + category + ", which is never partially revealed — the runtime forces it to 0. "
                        + "Remove the attribute so the declaration says what actually happens.");
    }
}
