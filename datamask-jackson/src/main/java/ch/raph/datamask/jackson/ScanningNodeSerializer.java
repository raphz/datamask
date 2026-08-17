package ch.raph.datamask.jackson;

import java.util.Map;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.jsontype.TypeSerializer;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.BaseJsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

/**
 * Runs the detectors over a {@code JsonNode} tree.
 *
 * <p>A tree writes itself: {@code ObjectNode.serialize} walks its children and calls
 * {@code serialize} on each one directly, so no serializer lookup ever happens for a node below the
 * root and no registration for {@code StringNode} alone would reach one. A tree is also exactly
 * where unclassified text ends up — a webhook payload, a stored document, an audit detail — so the
 * whole tree is masked here, and the masked copy is what writes itself.
 *
 * <p>The copy is built only where something changed; an unchanged subtree is reused, which keeps a
 * PII-free document allocation-free.
 *
 * <p><strong>A finding in a tree is unannotated PII, not a declared scan.</strong> The two signals
 * differ by whether somebody said the value holds free text, and a {@code JsonNode} member says the
 * opposite of that: it is a hole in the schema, a declaration that the <em>shape</em> is unknown,
 * never a statement about the content. A property that <em>was</em> declared —
 * {@code FREEFORM_TEXT}, or a {@code SCAN} strategy — never reaches this serializer at all, because
 * the modifier has already replaced its writer with a {@link MaskingSerializer} and the engine
 * routes it to {@code sanitizeDeclared}. So every string arriving here is one nobody classified,
 * which is exactly the alert-worthy case: an upstream payload has started carrying PII and the
 * contract needs a policy. That is also the switch it hangs off — {@code scanUnannotatedText}.
 */
final class ScanningNodeSerializer extends ValueSerializer<BaseJsonNode> {

    private final TextScanner scanner;
    private final boolean scanValues;
    private final boolean maskNames;

    ScanningNodeSerializer(TextScanner scanner, boolean scanValues, boolean maskNames) {
        this.scanner = scanner;
        this.scanValues = scanValues;
        this.maskNames = maskNames;
    }

    @Override
    public void serialize(BaseJsonNode value, JsonGenerator generator, SerializationContext context) {
        node(masked(value, TextScanner.treePathOf(generator))).serialize(generator, context);
    }

    @Override
    public void serializeWithType(
            BaseJsonNode value, JsonGenerator generator, SerializationContext context, TypeSerializer typeSerializer) {
        node(masked(value, TextScanner.treePathOf(generator))).serializeWithType(generator, context, typeSerializer);
    }

    @Override
    public Class<BaseJsonNode> handledType() {
        return BaseJsonNode.class;
    }

    private JsonNode masked(JsonNode node, String path) {
        return switch (node) {
            case ObjectNode object -> maskedObject(object, path);
            case ArrayNode array -> maskedArray(array, path);
            // A POJO node is left alone deliberately: it serialises its value through the context,
            // which brings it back through this module's property and string handling.
            default -> scanValues && node.isString() ? maskedString(node, path) : node;
        };
    }

    private JsonNode maskedObject(ObjectNode object, String path) {
        ObjectNode copy = JsonNodeFactory.instance.objectNode();
        boolean changed = false;
        for (Map.Entry<String, JsonNode> property : object.properties()) {
            // A property name in a free-form tree is data the same way a map key is, and it reaches
            // the document through a different route than the value does. The `{key}` suffix is the
            // engine's own convention for "the key of this thing", and it is what separates a name
            // from a value under the one `jackson:tree/...` site.
            String name = maskNames ? scanner.scan(property.getKey(), path + "{key}") : property.getKey();
            JsonNode value = masked(property.getValue(), path);
            changed |= !name.equals(property.getKey()) || value != property.getValue();
            copy.set(name, value);
        }
        return changed ? copy : object;
    }

    private JsonNode maskedArray(ArrayNode array, String path) {
        ArrayNode copy = JsonNodeFactory.instance.arrayNode(array.size());
        boolean changed = false;
        for (JsonNode element : array) {
            JsonNode value = masked(element, path);
            changed |= value != element;
            copy.add(value);
        }
        return changed ? copy : array;
    }

    private JsonNode maskedString(JsonNode text, String path) {
        String raw = text.stringValue();
        String scanned = scanner.scan(raw, path);
        return scanned.equals(raw) ? text : StringNode.valueOf(scanned);
    }

    /** Every node implementation extends {@link BaseJsonNode}, which is what can write itself. */
    private static BaseJsonNode node(JsonNode masked) {
        return (BaseJsonNode) masked;
    }
}
