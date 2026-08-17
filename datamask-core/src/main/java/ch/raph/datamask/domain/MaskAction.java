package ch.raph.datamask.domain;

/** What the engine does with one member of a compiled {@link MaskPlan}. */
public sealed interface MaskAction {

    /** Replace the value using the resolved declaration. */
    record Mask(PiiDescriptor descriptor) implements MaskAction {}

    /** Recurse: the value is a nested object, collection or map that may itself carry PII. */
    record Descend() implements MaskAction {}

    /** Copy the value across untouched. */
    record Keep() implements MaskAction {}

    /**
     * Omit the value entirely.
     *
     * <p>What a {@link PolicyOverrides} drop compiles to. Distinct from masking to a placeholder:
     * the engine rebuilds with {@code null} in its place, and a serializer leaves the property out
     * altogether, so not even the field's existence is disclosed.
     */
    record Drop() implements MaskAction {}

    MaskAction DESCEND = new Descend();
    MaskAction KEEP = new Keep();
    MaskAction DROP = new Drop();
}
