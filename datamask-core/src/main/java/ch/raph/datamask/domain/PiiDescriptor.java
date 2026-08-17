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
 *
 * <p>The canonical constructor is <strong>not the API</strong>. Eight positional components mean a
 * ninth would break every caller, and two of them are a {@code char} and an {@code int} that sit
 * next to each other, so a transposition compiles. Build one with {@link #of(PiiCategory)} or
 * {@link #redacting(PiiCategory)} and refine it with the {@code with*} methods; the constructor is
 * public only because a record's is, and because the compact constructor is where the
 * never-partially-revealed rule is enforced for every route in.
 *
 * @param category    what the value is
 * @param sensitivity how strictly it is treated; {@link Sensitivity#HIGH} is read as unset and the
 *                    category decides
 * @param strategy    how to mask it; {@link MaskStrategy#AUTO} resolves at masking time
 * @param keep        trailing characters to reveal, or -1 for the category's own default
 * @param padding     the character a partial mask pads with
 * @param replacement a fixed string to substitute, when the strategy uses one
 * @param maskerType  a custom {@link Masker}, or {@code Masker.class} for none
 * @param purpose     the key-derivation purpose, so pseudonyms issued for one use cannot be joined
 *                    against another's
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

    /**
     * The declaration a bare {@code @PII(category = …)} produces: everything left to the category
     * and to {@link MaskStrategy#AUTO}, which is the style this library recommends.
     */
    public static PiiDescriptor of(PiiCategory category) {
        return new PiiDescriptor(category, Sensitivity.HIGH, MaskStrategy.AUTO, -1, '*', "", Masker.class, "");
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

    /**
     * A sensitivity other than {@link Sensitivity#HIGH}, which the compact constructor reads as
     * unset. Lowering it below the deployment's threshold switches masking of this value off, which
     * is the point of the dial and the reason it is worth being explicit about.
     */
    public PiiDescriptor withSensitivity(Sensitivity newSensitivity) {
        return new PiiDescriptor(category, newSensitivity, strategy, keep, padding, replacement, maskerType, purpose);
    }

    /** Trailing characters to reveal, or -1 for the category's default. Ignored by a never-revealed category. */
    public PiiDescriptor withKeep(int newKeep) {
        return new PiiDescriptor(category, sensitivity, strategy, newKeep, padding, replacement, maskerType, purpose);
    }

    public PiiDescriptor withPadding(char newPadding) {
        return new PiiDescriptor(category, sensitivity, strategy, keep, newPadding, replacement, maskerType, purpose);
    }

    public PiiDescriptor withReplacement(String newReplacement) {
        return new PiiDescriptor(category, sensitivity, strategy, keep, padding, newReplacement, maskerType, purpose);
    }

    public PiiDescriptor withMasker(Class<? extends Masker> newMaskerType) {
        return new PiiDescriptor(category, sensitivity, strategy, keep, padding, replacement, newMaskerType, purpose);
    }

    /** The key-derivation purpose: a pseudonym issued under one cannot be joined against another's. */
    public PiiDescriptor withPurpose(String newPurpose) {
        return new PiiDescriptor(category, sensitivity, strategy, keep, padding, replacement, maskerType, newPurpose);
    }
}
