package ch.raph.datamask.jackson;

import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonToken;
import tools.jackson.core.type.WritableTypeId;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.jsontype.TypeSerializer;

/**
 * Runs the detectors over every string nobody declared.
 *
 * <p>This is the half that catches what annotations cannot: an IBAN a customer typed into a payment
 * reference, a card number in a support note, an email address inside a list of free-text lines.
 * Each hit is reported to the {@code MaskingObserver}, which is the earliest warning that a field
 * has started carrying PII nobody classified.
 *
 * <p>Registered for {@code CharSequence} as well as {@code String} rather than per property, so that
 * a value anywhere in the document is covered — inside a collection, as a map value, at the root, or
 * held in a {@code StringBuilder}. Properties the plan already decided on never arrive here.
 */
final class ScanningStringSerializer extends ValueSerializer<CharSequence> {

    private final TextScanner scanner;

    ScanningStringSerializer(TextScanner scanner) {
        this.scanner = scanner;
    }

    @Override
    public void serialize(CharSequence value, JsonGenerator generator, SerializationContext context) {
        generator.writeString(scanner.scan(value, TextScanner.pathOf(generator)));
    }

    /**
     * The polymorphic path, for a string that landed in a slot carrying {@code @JsonTypeInfo}. The
     * base {@code ValueSerializer} refuses to write a type id and would abort the document, so a
     * string in a polymorphic slot has to be handled here — a scanner that only works outside
     * polymorphism would be a scanner an application has to switch off.
     */
    @Override
    public void serializeWithType(
            CharSequence value, JsonGenerator generator, SerializationContext context, TypeSerializer typeSerializer) {
        String masked = scanner.scan(value, TextScanner.pathOf(generator));
        WritableTypeId typeId = typeSerializer.writeTypePrefix(
                generator, context, typeSerializer.typeId(masked, JsonToken.VALUE_STRING));
        generator.writeString(masked);
        typeSerializer.writeTypeSuffix(generator, context, typeId);
    }

    @Override
    public Class<CharSequence> handledType() {
        return CharSequence.class;
    }
}
