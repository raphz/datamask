package ch.raph.datamask.jackson;

import ch.raph.datamask.application.MaskingEngine;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.TokenStreamContext;

/**
 * Runs the detectors over one string, and never lets that fail the document.
 *
 * <p>Shared by everything in this module that writes a string nobody declared: a property value, a
 * map key, a string inside a JSON tree. Declared free text never arrives here — the modifier gives
 * such a property its own {@link MaskingSerializer}, and the engine routes a {@code SCAN} strategy
 * to {@code TextSanitizer.sanitizeDeclared} itself. So every finding this class produces is one
 * nobody classified, and {@code onUnannotatedPii} is the right signal for all of it.
 *
 * <p>This is also where the module's path grammar lives: {@code jackson:<site>[/<detail>]}, one
 * scheme per module so a rule keying on the prefix can tell which integration reported a finding.
 * The sites are {@code text}, {@code key}, {@code tree} and — for declared PII, built by
 * {@link MaskingSerializerModifier} — the bean's own simple name.
 */
final class TextScanner {

    /** The scheme every path this module reports carries. */
    static final String MODULE = "jackson";

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
     * {@code jackson:<site>/<detail>}, or {@code jackson:<site>} when there is no detail to give.
     *
     * <p>The one place the scheme is written, so the module cannot drift into reporting half its
     * findings under a prefix a SIEM rule does not recognise.
     */
    static String path(String site, String detail) {
        return detail == null ? MODULE + ":" + site : MODULE + ":" + site + "/" + detail;
    }

    /**
     * {@code jackson:text/<property>} for a string nobody declared — the enclosing property name is
     * what an observer needs in order to find the field that leaked. Deliberately not the full JSON
     * pointer: this runs on every string in every document, and building a pointer would allocate
     * on each one.
     */
    static String pathOf(JsonGenerator generator) {
        return path("text", nameFrom(generator.streamWriteContext()));
    }

    /**
     * {@code jackson:tree/<property>} for a value inside a {@code JsonNode}, which is a different
     * kind of site from a typed property: nothing in the schema describes it, so a finding there
     * says an upstream payload started carrying PII rather than that a field of ours did.
     */
    static String treePathOf(JsonGenerator generator) {
        return path("tree", nameFrom(generator.streamWriteContext()));
    }

    /**
     * {@code jackson:key/<property>} for a map key, whose enclosing name starts one context out.
     *
     * <p>The name currently set on the object being written is the <em>previous</em> key of the
     * same map — often the very PII this library exists to hide. Paths reach observers and
     * exception messages, so the enclosing property name is used instead, exactly as the engine
     * keeps map paths positional. The site already says the value was a key, so no {@code {key}}
     * suffix is needed on top of it.
     */
    static String keyPathOf(JsonGenerator generator) {
        TokenStreamContext object = generator.streamWriteContext();
        return path("key", nameFrom(object == null ? null : object.getParent()));
    }

    /** {@code null} when the value is being written outside any named property, such as at the root. */
    private static String nameFrom(TokenStreamContext start) {
        for (TokenStreamContext context = start; context != null; context = context.getParent()) {
            if (context.hasCurrentName()) {
                return context.currentName();
            }
        }
        return null;
    }
}
