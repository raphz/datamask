package ch.raph.datamask.jackson;

import ch.raph.datamask.application.DataMask;
import ch.raph.datamask.application.MaskingEngine;
import java.util.Objects;
import tools.jackson.core.Version;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.module.SimpleSerializers;

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
 * single decision per property. Everything else — a string in a list, a map value, a property
 * nobody annotated — goes through the detectors, so an IBAN that ended up in a free-text field is
 * still caught. That second half is governed by {@code MaskingPolicy#scanUnannotatedText()} and
 * disappears entirely when it is off.
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

        // Registered for String rather than per property, so a value in a list, a map or at the
        // root of the document is scanned as well. Properties the plan already decided on never
        // reach it: they are given their own serializer below.
        if (engine.policy().scanUnannotatedText()) {
            context.addSerializers(
                    new SimpleSerializers().addSerializer(String.class, new ScanningStringSerializer(engine)));
        }
    }
}
