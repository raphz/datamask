package ch.raph.datamask.jackson;

import ch.raph.datamask.application.MaskingEngine;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.TokenStreamContext;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

/**
 * Runs the detectors over every string nobody declared.
 *
 * <p>This is the half that catches what annotations cannot: an IBAN a customer typed into a payment
 * reference, a card number in a support note, an email address inside a list of free-text lines.
 * Each hit is reported to the {@code MaskingObserver}, which is the earliest warning that a field
 * has started carrying PII nobody classified.
 *
 * <p>Registered for {@code String} rather than per property so that a value anywhere in the
 * document is covered — inside a collection, as a map value, or at the root. Properties the plan
 * already decided on never arrive here.
 */
final class ScanningStringSerializer extends ValueSerializer<String> {

    private final MaskingEngine engine;

    ScanningStringSerializer(MaskingEngine engine) {
        this.engine = engine;
    }

    @Override
    public void serialize(String value, JsonGenerator generator, SerializationContext context) {
        generator.writeString(engine.maskText(value, pathOf(generator)));
    }

    @Override
    public Class<String> handledType() {
        return String.class;
    }

    /**
     * The enclosing property name, which is what an observer needs in order to find the field that
     * leaked. Deliberately not the full JSON pointer: this runs on every string in every document,
     * and building a pointer would allocate on each one.
     */
    private static String pathOf(JsonGenerator generator) {
        for (TokenStreamContext context = generator.streamWriteContext();
                context != null;
                context = context.getParent()) {
            if (context.hasCurrentName()) {
                return context.currentName();
            }
        }
        return "text";
    }
}
