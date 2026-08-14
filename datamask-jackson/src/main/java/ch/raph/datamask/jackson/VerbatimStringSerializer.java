package ch.raph.datamask.jackson;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

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

    @Override
    public Class<Object> handledType() {
        return Object.class;
    }
}
