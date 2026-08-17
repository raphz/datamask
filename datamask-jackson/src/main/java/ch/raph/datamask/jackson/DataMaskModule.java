package ch.raph.datamask.jackson;

import ch.raph.datamask.application.DataMask;
import ch.raph.datamask.application.MaskingEngine;
import ch.raph.datamask.domain.MaskingPolicy;
import java.util.Objects;
import tools.jackson.core.Version;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.module.SimpleSerializers;
import tools.jackson.databind.node.BaseJsonNode;

/**
 * Masks PII on the way out to JSON, so the raw value is never written and never has to be scrubbed
 * afterwards.
 *
 * {@snippet :
 * DataMask dataMask = DataMask.builder().secret(System.getenv("DATAMASK_SECRET")).build();
 *
 * ObjectMapper mapper = JsonMapper.builder()
 *         .addModule(new DataMaskModule(dataMask))
 *         .build();
 * }
 *
 * <p>Nothing else changes: the domain model keeps its {@code @PII} annotations, no call site is
 * touched, and no masked copy of the object graph is built. Masking happens as each property is
 * written, which is the one place every serialised value has to pass through.
 *
 * <p>Two things are hooked. Declared PII is handled from the compiled {@code MaskPlan}, which is
 * where {@code @PII}, {@code @NoMask} and any policy override have already been resolved into a
 * single decision per property. Everything else — a string in a list, a map value, a map key, a
 * {@code JsonNode} tree, a property nobody annotated — goes through the detectors, so an IBAN that
 * ended up in a free-text field is still caught. That second half is governed by
 * {@code MaskingPolicy#scanUnannotatedText()} and {@code MaskingPolicy#maskMapKeys()}, and
 * disappears entirely when both are off.
 *
 * <p>Deserialization is untouched on purpose. This module protects what leaves the process; masking
 * on the way in would silently destroy data the application is meant to store.
 */
public final class DataMaskModule extends JacksonModule {

    private final MaskingEngine engine;

    public DataMaskModule(DataMask dataMask) {
        this(Objects.requireNonNull(dataMask, "dataMask").engine());
    }

    public DataMaskModule(MaskingEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    @Override
    public String getModuleName() {
        return "datamask";
    }

    @Override
    public Version version() {
        return Version.unknownVersion();
    }

    @Override
    public void setupModule(SetupContext context) {
        context.addSerializerModifier(new MaskingSerializerModifier(engine));

        MaskingPolicy policy = engine.policy();
        // A map key is written through a lookup of its own, so nothing registered below would ever
        // see it. Masking a key changes what an entry can be looked up by, which is why the engine
        // makes it opt-in — but a document being written is not a map being read, so either switch
        // is enough here.
        boolean maskKeys = policy.maskMapKeys() || policy.scanUnannotatedText();
        if (!maskKeys) {
            return;
        }

        TextScanner scanner = new TextScanner(engine);
        MaskingKeySerializer keys = new MaskingKeySerializer(scanner);
        context.addKeySerializers(
                new SimpleSerializers().addSerializer(String.class, keys).addSerializer(CharSequence.class, keys));

        // Registered by type rather than per property, so a value in a list, a map, a JSON tree or
        // at the root of the document is scanned as well. Properties the plan already decided on
        // never reach these: they are given their own serializer by the modifier above.
        SimpleSerializers serializers = new SimpleSerializers()
                .addSerializer(
                        BaseJsonNode.class,
                        new ScanningNodeSerializer(scanner, policy.scanUnannotatedText(), maskKeys));
        if (policy.scanUnannotatedText()) {
            ScanningStringSerializer text = new ScanningStringSerializer(scanner);
            serializers.addSerializer(String.class, text).addSerializer(CharSequence.class, text);
        }
        context.addSerializers(serializers);
    }
}
