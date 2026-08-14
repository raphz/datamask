package ch.raph.datamask.jackson;

import ch.raph.datamask.application.MaskingEngine;
import ch.raph.datamask.domain.PiiDescriptor;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.jsontype.TypeSerializer;

/**
 * Writes one declared PII property, masked.
 *
 * <p>Everything that decides <em>how</em> — the category, the strategy, the policy threshold and
 * the failure mode — was settled when the plan was compiled and is applied by the engine. This
 * class only has to make sure the raw value never reaches the generator.
 */
final class MaskingSerializer extends ValueSerializer<Object> {

    private final MaskingEngine engine;
    private final PiiDescriptor descriptor;
    private final Class<?> declaredType;
    private final String path;

    MaskingSerializer(MaskingEngine engine, PiiDescriptor descriptor, Class<?> declaredType, String path) {
        this.engine = engine;
        this.descriptor = descriptor;
        this.declaredType = declaredType;
        this.path = path;
    }

    @Override
    public void serialize(Object value, JsonGenerator generator, SerializationContext context) {
        write(engine.maskDeclared(value, descriptor, declaredType, path), generator, context);
    }

    /**
     * The polymorphic path, for a property carrying {@code @JsonTypeInfo}. The masked value decides
     * the serializer, because a strategy is free to return something of a different type than it
     * was given.
     */
    @Override
    public void serializeWithType(
            Object value, JsonGenerator generator, SerializationContext context, TypeSerializer typeSerializer) {
        Object masked = engine.maskDeclared(value, descriptor, declaredType, path);
        if (masked == null) {
            generator.writeNull();
            return;
        }
        context.findValueSerializer(masked.getClass()).serializeWithType(masked, generator, context, typeSerializer);
    }

    @Override
    public Class<Object> handledType() {
        return Object.class;
    }

    private static void write(Object masked, JsonGenerator generator, SerializationContext context) {
        switch (masked) {
            // A strategy that removes the value outright — NULLIFY, or a failure resolved by
            // dropping the field. The property still appears, holding null, so a consumer can tell
            // the difference between "withheld" and "not part of this payload".
            case null -> generator.writeNull();
            // Written straight out rather than delegated: this string has already been masked, and
            // sending it back through the scanner would only re-examine a value that is safe by
            // construction.
            case String text -> generator.writeString(text);
            default -> context.writeValue(generator, masked);
        }
    }
}
