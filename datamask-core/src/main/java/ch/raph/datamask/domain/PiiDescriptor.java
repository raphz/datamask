package ch.raph.datamask.domain;

import ch.raph.datamask.api.MaskStrategy;
import ch.raph.datamask.api.Masker;
import ch.raph.datamask.api.PII;
import ch.raph.datamask.api.PiiCategory;
import ch.raph.datamask.api.Sensitivity;
import java.util.Objects;

/**
 * The fully resolved masking declaration for a single value: what an annotation, a type-level
 * annotation and an external policy collapse into once merged.
 */
public record PiiDescriptor(
        PiiCategory category,
        Sensitivity sensitivity,
        MaskStrategy strategy,
        int keep,
        char padding,
        String replacement,
        Class<? extends Masker> maskerType,
        String purpose) {

    public PiiDescriptor {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(sensitivity, "sensitivity");
        Objects.requireNonNull(strategy, "strategy");
        Objects.requireNonNull(replacement, "replacement");
        Objects.requireNonNull(maskerType, "maskerType");
        Objects.requireNonNull(purpose, "purpose");

        // HIGH is the annotation default, so it is read as "unset" and the category decides: a PAN
        // or national id then classifies as CRITICAL, out of reach of any masking threshold. An
        // explicit non-default sensitivity is respected — that dial exists for exactly that.
        // Enforced here, in the one constructor every code path shares — annotation, override,
        // generated plan — rather than trusted to each of them.
        if (sensitivity == Sensitivity.HIGH) {
            sensitivity = category.defaultSensitivity();
        }
        // A category that must never be partially revealed overrides whatever the annotation or a
        // policy asked for. Getting this wrong on card verification values is a PCI-DSS finding,
        // so it is enforced here rather than trusted to every masker. Sensitivity is pinned to
        // CRITICAL for the same reason: no policy threshold may switch these values' masking off.
        if (category.neverPartiallyReveal()) {
            keep = 0;
            sensitivity = Sensitivity.CRITICAL;
        }
        if (keep < -1) {
            throw new IllegalArgumentException("keep must be -1 (category default) or >= 0, was " + keep);
        }
    }

    public static PiiDescriptor from(PII annotation) {
        return new PiiDescriptor(
                annotation.category(),
                annotation.sensitivity(),
                annotation.strategy(),
                annotation.keep(),
                annotation.padding(),
                annotation.replacement(),
                annotation.masker(),
                annotation.purpose());
    }

    /** A descriptor that fully redacts, used as the fail-closed fallback. */
    public static PiiDescriptor redacting(PiiCategory category) {
        return new PiiDescriptor(category, Sensitivity.HIGH, MaskStrategy.REDACT, 0, '*', "", Masker.class, "");
    }

    /** Whether a custom {@link Masker} implementation was named on the annotation. */
    public boolean hasCustomMasker() {
        return maskerType != Masker.class;
    }

    /** How many trailing characters to keep, falling back to the category's default. */
    public int effectiveKeep() {
        if (category.neverPartiallyReveal()) {
            return 0;
        }
        return keep >= 0 ? keep : Math.max(category.defaultKeep(), 0);
    }

    public PiiDescriptor withStrategy(MaskStrategy newStrategy) {
        return new PiiDescriptor(category, sensitivity, newStrategy, keep, padding, replacement, maskerType, purpose);
    }

    public PiiDescriptor withCategory(PiiCategory newCategory) {
        return new PiiDescriptor(newCategory, sensitivity, strategy, keep, padding, replacement, maskerType, purpose);
    }
}
