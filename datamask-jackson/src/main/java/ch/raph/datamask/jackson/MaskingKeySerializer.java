package ch.raph.datamask.jackson;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

/**
 * Runs the detectors over a map key.
 *
 * <p>Jackson writes a key through a key serializer, which is a different lookup from the one that
 * serves values — so a {@code Map<String, Balance>} keyed by IBAN would write every account number
 * into the document untouched while its values were being masked. A map keyed by exactly the value
 * that identifies the customer is a common shape, which makes this the wider of the two holes.
 *
 * <p>Masking a key changes what a consumer can look the entry up by, which is why the engine makes
 * it opt-in. At this boundary nothing is looked up: the document is being written, the application's
 * own map is untouched, so the key is masked whenever either content scanning or key masking is on.
 */
final class MaskingKeySerializer extends ValueSerializer<Object> {

    private final TextScanner scanner;

    MaskingKeySerializer(TextScanner scanner) {
        this.scanner = scanner;
    }

    @Override
    public void serialize(Object value, JsonGenerator generator, SerializationContext context) {
        generator.writeName(scanner.scan(value.toString(), TextScanner.keyPathOf(generator)));
    }

    @Override
    public Class<Object> handledType() {
        return Object.class;
    }
}
