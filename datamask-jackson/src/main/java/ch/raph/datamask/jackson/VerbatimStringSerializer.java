package ch.raph.datamask.jackson;

import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonToken;
import tools.jackson.core.type.WritableTypeId;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.jsontype.TypeSerializer;

/**
 * Writes a string exactly as given.
 *
 * <p>Assigned only to a property carrying {@code @NoMask}, to keep it out of the scanner. An
 * exemption that a detector could still rewrite would not be an exemption.
 */
final class VerbatimStringSerializer extends ValueSerializer<Object> {

    static final VerbatimStringSerializer INSTANCE = new VerbatimStringSerializer();

    private VerbatimStringSerializer() {}

    @Override
    public void serialize(Object value, JsonGenerator generator, SerializationContext context) {
        generator.writeString(value.toString());
    }

    /** For an exempt value in a slot carrying {@code @JsonTypeInfo}; the base class would abort. */
    @Override
    public void serializeWithType(
            Object value, JsonGenerator generator, SerializationContext context, TypeSerializer typeSerializer) {
        String text = value.toString();
        WritableTypeId typeId =
                typeSerializer.writeTypePrefix(generator, context, typeSerializer.typeId(text, JsonToken.VALUE_STRING));
        generator.writeString(text);
        typeSerializer.writeTypeSuffix(generator, context, typeId);
    }

    @Override
    public Class<Object> handledType() {
        return Object.class;
    }
}
