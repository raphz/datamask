package ch.raph.datamask.jackson;

import ch.raph.datamask.application.MaskingEngine;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.TokenStreamContext;

/**
 * Runs the detectors over one string, and never lets that fail the document.
 *
 * <p>Shared by everything in this module that writes a string nobody declared: a property value, a
 * map key, a string inside a JSON tree.
 */
final class TextScanner {

    private final MaskingEngine engine;

    TextScanner(MaskingEngine engine) {
        this.engine = engine;
    }

    /**
     * The text with every detected value masked, or the redaction placeholder when the scan itself
     * failed — never the text it failed to mask.
     *
     * <p>A custom masker reached through a detector finding is application code, and it throwing
     * must not turn a response into a 500. It must not turn into a disclosure either, so the whole
     * string is withheld rather than passed through, and the failure is reported where an operator
     * will see it. This is the same bargain the logging integrations strike.
     */
    String scan(CharSequence text, String path) {
        try {
            return engine.maskText(text, path);
        } catch (RuntimeException failure) {
            engine.observer().onFailure(path, failure);
            return engine.policy().redactionPlaceholder();
        }
    }

    /**
     * The enclosing property name, which is what an observer needs in order to find the field that
     * leaked. Deliberately not the full JSON pointer: this runs on every string in every document,
     * and building a pointer would allocate on each one.
     */
    static String pathOf(JsonGenerator generator) {
        return nameFrom(generator.streamWriteContext(), "text");
    }

    /**
     * The path for a map key, which starts one context out.
     *
     * <p>The name currently set on the object being written is the <em>previous</em> key of the
     * same map — often the very PII this library exists to hide. Paths reach observers and
     * exception messages, so the enclosing property name is used instead, exactly as the engine
     * keeps map paths positional.
     */
    static String keyPathOf(JsonGenerator generator) {
        TokenStreamContext object = generator.streamWriteContext();
        return nameFrom(object == null ? null : object.getParent(), "key") + "{key}";
    }

    private static String nameFrom(TokenStreamContext start, String fallback) {
        for (TokenStreamContext context = start; context != null; context = context.getParent()) {
            if (context.hasCurrentName()) {
                return context.currentName();
            }
        }
        return fallback;
    }
}
