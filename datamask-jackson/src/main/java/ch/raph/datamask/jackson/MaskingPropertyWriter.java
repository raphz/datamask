package ch.raph.datamask.jackson;

import tools.jackson.databind.PropertyName;
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

    private MaskingPropertyWriter(MaskingPropertyWriter base, PropertyName name) {
        super(base, name);
    }

    /**
     * Renaming happens when the property is reached through {@code @JsonUnwrapped} with a prefix or
     * a suffix. {@code BeanPropertyWriter._new} refuses to guess how to copy a subclass and throws,
     * so without this override an unwrapped prefix would abort the document rather than write a
     * masked value — and a serializer that cannot be built is a serializer nobody keeps.
     *
     * <p>The copy constructor carries the masking serializer across, so the renamed property is
     * still masked.
     */
    @Override
    protected BeanPropertyWriter _new(PropertyName newName) {
        return new MaskingPropertyWriter(this, newName);
    }
}
