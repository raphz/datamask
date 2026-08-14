package ch.raph.datamask.jackson;

import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.ser.BeanPropertyWriter;

/**
 * A property writer that always renders its value through the masking serializer.
 *
 * <p>{@code BeanPropertyWriter.assignSerializer} refuses to replace a serializer a property already
 * has, which is the right default — but it would mean that a property declaring both {@code @PII}
 * and {@code @JsonSerialize(using = ...)} could not be masked at all. Copying the writer and
 * setting the serializer directly resolves that in the only direction that is safe: the declaration
 * that a value <em>is</em> PII outranks any declaration of how to render it.
 *
 * <p>Everything else the property carries — inclusion rules, views, its null serializer, its name —
 * is copied across unchanged.
 */
final class MaskingPropertyWriter extends BeanPropertyWriter {

    private static final long serialVersionUID = 1L;

    MaskingPropertyWriter(BeanPropertyWriter base, ValueSerializer<Object> masking) {
        super(base);
        this._serializer = masking;
    }
}
