package ch.raph.datamask.jackson;

import ch.raph.datamask.application.MaskingEngine;
import ch.raph.datamask.domain.MaskAction;
import ch.raph.datamask.domain.MaskPlan;
import ch.raph.datamask.domain.MemberPlan;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.SerializationConfig;
import tools.jackson.databind.introspect.AnnotatedMember;
import tools.jackson.databind.ser.BeanPropertyWriter;
import tools.jackson.databind.ser.ValueSerializerModifier;

/**
 * Applies the compiled {@link MaskPlan} for a bean to the writers Jackson is about to use for it.
 *
 * <p>This runs once per type, while the serializer for it is being built, so the reflective plan
 * lookup is off the serialization path entirely. Afterwards each property carries its own decision
 * and masking costs one virtual call.
 */
final class MaskingSerializerModifier extends ValueSerializerModifier {

    private final MaskingEngine engine;

    MaskingSerializerModifier(MaskingEngine engine) {
        this.engine = engine;
    }

    @Override
    public List<BeanPropertyWriter> changeProperties(
            SerializationConfig config, BeanDescription.Supplier beanDescRef, List<BeanPropertyWriter> properties) {

        Class<?> beanClass = beanDescRef.getBeanClass();
        MaskPlan plan = engine.compiler().planFor(beanClass);
        if (plan.isOpaque()) {
            return properties;
        }

        Map<String, MemberPlan> byName = HashMap.newHashMap(plan.members().size());
        for (MemberPlan member : plan.members()) {
            byName.putIfAbsent(member.name(), member);
        }

        List<BeanPropertyWriter> rewritten = new ArrayList<>(properties.size());
        for (BeanPropertyWriter writer : properties) {
            MemberPlan member = find(byName, writer);
            if (member == null) {
                // A virtual or computed property the plan never saw — an @JsonProperty on a method
                // with no backing field. Content scanning is the only thing covering it, which is
                // exactly the case it exists for.
                rewritten.add(writer);
                continue;
            }

            BeanPropertyWriter replacement =
                    switch (member.action()) {
                        case MaskAction.Mask mask -> {
                            // The path has the same shape the engine gives a root-level member, so
                            // an observer reads the same whether the value was masked here or by a
                            // graph walk.
                            String path = beanClass.getSimpleName() + "." + member.name();
                            yield new MaskingPropertyWriter(
                                    writer,
                                    new MaskingSerializer(engine, mask.descriptor(), member.declaredType(), path));
                        }
                        // Not written at all — the only form of masking that leaves no trace of the
                        // field in the document.
                        case MaskAction.Drop ignored -> null;
                        case MaskAction.Keep ignored -> exemptFromScanning(writer);
                        // Nested beans are reached by Jackson's own traversal, which brings this
                        // modifier back for their type; strings fall through to the scanner.
                        case MaskAction.Descend ignored -> writer;
                    };

            if (replacement != null) {
                rewritten.add(replacement);
            }
        }
        return rewritten;
    }

    /**
     * Keeps a {@code @NoMask} string out of the detectors' hands as well. Without this the
     * exemption would be honoured only halfway: the value would escape its declared masking and
     * then be rewritten anyway by whatever a detector thought it recognised.
     *
     * <p>A property that brought its own serializer is left with it. Unlike the masking case there
     * is nothing to enforce here, so the developer's explicit choice stands.
     */
    private static BeanPropertyWriter exemptFromScanning(BeanPropertyWriter writer) {
        if (!writer.hasSerializer()
                && CharSequence.class.isAssignableFrom(writer.getType().getRawClass())) {
            writer.assignSerializer(VerbatimStringSerializer.INSTANCE);
        }
        return writer;
    }

    /**
     * Matches a Jackson property back to the plan member it came from.
     *
     * <p>The member's own name is tried first, because it is the only candidate that cannot be
     * ambiguous: it is the field name, or a record component's accessor. The JSON name comes next,
     * which is what {@code @JsonProperty} renames. Stripping a {@code get}/{@code is} prefix is
     * last, since it is a guess — {@code issue()} would otherwise be read as a getter for
     * {@code sue}.
     */
    private static MemberPlan find(Map<String, MemberPlan> byName, BeanPropertyWriter writer) {
        AnnotatedMember member = writer.getMember();
        if (member != null) {
            MemberPlan direct = byName.get(member.getName());
            if (direct != null) {
                return direct;
            }
        }
        MemberPlan renamed = byName.get(writer.getName());
        if (renamed != null) {
            return renamed;
        }
        return member == null ? null : byName.get(withoutAccessorPrefix(member.getName()));
    }

    private static String withoutAccessorPrefix(String methodName) {
        if (methodName.startsWith("get") && methodName.length() > 3) {
            return decapitalize(methodName.substring(3));
        }
        if (methodName.startsWith("is") && methodName.length() > 2) {
            return decapitalize(methodName.substring(2));
        }
        return methodName;
    }

    private static String decapitalize(String name) {
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }
}
