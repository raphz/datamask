package ch.raph.datamask.infrastructure.reflect;

import ch.raph.datamask.api.NoMask;
import ch.raph.datamask.api.PII;
import ch.raph.datamask.application.MaskPlanCompiler;
import ch.raph.datamask.domain.MaskAction;
import ch.raph.datamask.domain.MaskPlan;
import ch.raph.datamask.domain.MaskingException;
import ch.raph.datamask.domain.MemberPlan;
import ch.raph.datamask.domain.PiiDescriptor;
import ch.raph.datamask.domain.PolicyOverrides;
import ch.raph.datamask.domain.ValueRebuilder;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Derives masking plans by reflection, once per class, and caches them in a {@link ClassValue}.
 *
 * <p>{@code ClassValue} rather than a map keyed by {@code Class}: it is the only cache that lets a
 * class and its plan be collected together when the classloader that defined them goes away, which
 * matters in any container that redeploys.
 *
 * <p>After the first instance of a type, masking costs a handful of {@code MethodHandle}
 * invocations and one constructor call — no annotation lookup, no field scan.
 */
public final class ReflectiveMaskPlanCompiler implements MaskPlanCompiler {

    private final PolicyOverrides overrides;
    private final ClassValue<MaskPlan> plans;

    public ReflectiveMaskPlanCompiler() {
        this(PolicyOverrides.none());
    }

    public ReflectiveMaskPlanCompiler(PolicyOverrides overrides) {
        this.overrides = overrides;
        this.plans = new ClassValue<>() {
            @Override
            protected MaskPlan computeValue(Class<?> type) {
                return compile(type);
            }
        };
    }

    @Override
    public MaskPlan planFor(Class<?> type) {
        return plans.get(type);
    }

    private MaskPlan compile(Class<?> type) {
        if (Types.isLeaf(type)
                || Types.isContainer(type)
                || type.isInterface()
                || type.isAnnotation()
                || java.lang.reflect.Proxy.isProxyClass(type)) {
            return MaskPlan.opaque(type);
        }
        return type.isRecord() ? compileRecord(type) : compileBean(type);
    }

    private MaskPlan compileRecord(Class<?> type) {
        RecordComponent[] components = type.getRecordComponents();
        MethodHandles.Lookup lookup = lookupFor(type);

        List<MemberPlan> members = new ArrayList<>(components.length);
        Class<?>[] parameterTypes = new Class<?>[components.length];
        try {
            for (int i = 0; i < components.length; i++) {
                RecordComponent component = components[i];
                parameterTypes[i] = component.getType();
                MethodHandle accessor = lookup.unreflect(component.getAccessor());
                members.add(new MemberPlan(
                        component.getName(),
                        component.getType(),
                        target -> accessor.invoke(target),
                        actionFor(
                                type,
                                component.getName(),
                                component.getType(),
                                component.getAnnotation(PII.class),
                                component.getAnnotation(NoMask.class))));
            }

            MethodHandle canonical = lookup.findConstructor(type, MethodType.methodType(void.class, parameterTypes))
                    .asSpreader(Object[].class, components.length);
            return new MaskPlan(type, members, (original, values) -> canonical.invoke(values));
        } catch (IllegalAccessException | NoSuchMethodException e) {
            // An inaccessible record — a private record in a module that is not open to us. Passing
            // it through would leak, so the plan refuses to rebuild and the engine redacts instead.
            return unrebuildable(type, "record components are not accessible", e);
        }
    }

    private MaskPlan compileBean(Class<?> type) {
        List<Field> fields = instanceFields(type);
        if (fields.isEmpty()) {
            return MaskPlan.opaque(type);
        }

        MethodHandles.Lookup lookup = lookupFor(type);
        List<MemberPlan> members = new ArrayList<>(fields.size());
        try {
            for (Field field : fields) {
                MethodHandle getter = lookup.unreflectGetter(field);
                members.add(new MemberPlan(
                        field.getName(),
                        field.getType(),
                        target -> getter.invoke(target),
                        actionFor(
                                type,
                                field.getName(),
                                field.getType(),
                                annotationOn(type, field),
                                noMaskOn(type, field))));
            }
        } catch (IllegalAccessException e) {
            return unrebuildable(type, "fields are not accessible", e);
        }

        return new MaskPlan(type, members, rebuilderFor(type, fields, lookup));
    }

    /**
     * Beans are rebuilt by an all-arguments constructor when one matches the field order — which is
     * what Lombok's {@code @AllArgsConstructor} and Jackson's {@code @ConstructorProperties} both
     * produce — and otherwise by a no-argument constructor plus field writes.
     */
    private ValueRebuilder rebuilderFor(Class<?> type, List<Field> fields, MethodHandles.Lookup lookup) {
        Class<?>[] fieldTypes = fields.stream().map(Field::getType).toArray(Class<?>[]::new);

        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (java.util.Arrays.equals(constructor.getParameterTypes(), fieldTypes)) {
                try {
                    MethodHandle handle =
                            lookup.unreflectConstructor(constructor).asSpreader(Object[].class, fieldTypes.length);
                    return (original, values) -> handle.invoke(values);
                } catch (IllegalAccessException ignored) {
                    // Try the next candidate.
                }
            }
        }

        try {
            MethodHandle noArgs = lookup.findConstructor(type, MethodType.methodType(void.class));
            List<MethodHandle> setters = new ArrayList<>(fields.size());
            for (Field field : fields) {
                setters.add(lookup.unreflectSetter(field));
            }
            return (original, values) -> {
                Object copy = noArgs.invoke();
                for (int i = 0; i < values.length; i++) {
                    setters.get(i).invoke(copy, values[i]);
                }
                return copy;
            };
        } catch (IllegalAccessException | NoSuchMethodException e) {
            return unrebuildableRebuilder(
                    type,
                    "no all-arguments constructor matching the field order and no accessible no-argument constructor");
        }
    }

    private MaskAction actionFor(Class<?> owner, String name, Class<?> declaredType, PII annotation, NoMask exemption) {
        if (exemption != null) {
            return MaskAction.KEEP;
        }

        Optional<PiiDescriptor> memberOverride = overrides.forMember(owner, name);
        if (memberOverride.isPresent()) {
            return new MaskAction.Mask(memberOverride.get());
        }
        PII onType = declaredType.getAnnotation(PII.class);
        if (annotation != null) {
            PiiDescriptor descriptor = PiiDescriptor.from(annotation);
            // A bare `@PII Email email` defers to whatever the Email type itself declares, which is
            // both more precise and cheaper than working the category out from the value's content.
            return new MaskAction.Mask(isBare(descriptor) && onType != null ? PiiDescriptor.from(onType) : descriptor);
        }
        if (onType != null) {
            return new MaskAction.Mask(PiiDescriptor.from(onType));
        }
        Optional<PiiDescriptor> typeOverride = overrides.forType(declaredType);
        if (typeOverride.isPresent()) {
            return new MaskAction.Mask(typeOverride.get());
        }

        // Unannotated text still descends: the policy may have content scanning enabled, which is
        // how PII that nobody declared gets caught.
        if (CharSequence.class.isAssignableFrom(declaredType)) {
            return MaskAction.DESCEND;
        }
        return Types.isLeaf(declaredType) ? MaskAction.KEEP : MaskAction.DESCEND;
    }

    /** Whether the annotation adds nothing of its own and is purely a marker. */
    private static boolean isBare(PiiDescriptor descriptor) {
        return descriptor.strategy() == ch.raph.datamask.api.MaskStrategy.AUTO
                && descriptor.category() == ch.raph.datamask.api.PiiCategory.UNSPECIFIED
                && !descriptor.hasCustomMasker()
                && descriptor.replacement().isEmpty()
                && descriptor.keep() == -1;
    }

    /** Reads {@code @PII} from the field or, failing that, from its getter. */
    private static PII annotationOn(Class<?> type, Field field) {
        PII onField = field.getAnnotation(PII.class);
        if (onField != null) {
            return onField;
        }
        Method getter = getterFor(type, field);
        return getter == null ? null : getter.getAnnotation(PII.class);
    }

    private static NoMask noMaskOn(Class<?> type, Field field) {
        NoMask onField = field.getAnnotation(NoMask.class);
        if (onField != null) {
            return onField;
        }
        Method getter = getterFor(type, field);
        return getter == null ? null : getter.getAnnotation(NoMask.class);
    }

    private static Method getterFor(Class<?> type, Field field) {
        String capitalised = Character.toUpperCase(field.getName().charAt(0))
                + field.getName().substring(1);
        for (String candidate : List.of("get" + capitalised, "is" + capitalised, field.getName())) {
            try {
                return type.getMethod(candidate);
            } catch (NoSuchMethodException ignored) {
                // Try the next naming convention.
            }
        }
        return null;
    }

    private static List<Field> instanceFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) {
                    fields.add(field);
                }
            }
        }
        return fields;
    }

    /**
     * A private lookup sees private members of classes on the classpath. When the target lives in a
     * module that does not open its packages, this is refused and only public members are reachable
     * — records still work, because their accessors are public.
     */
    private static MethodHandles.Lookup lookupFor(Class<?> type) {
        try {
            return MethodHandles.privateLookupIn(type, MethodHandles.lookup());
        } catch (IllegalAccessException | SecurityException e) {
            return MethodHandles.publicLookup();
        }
    }

    private static MaskPlan unrebuildable(Class<?> type, String reason, Throwable cause) {
        return new MaskPlan(type, List.of(), unrebuildableRebuilder(type, reason + ": " + cause.getMessage()));
    }

    private static ValueRebuilder unrebuildableRebuilder(Class<?> type, String reason) {
        return (original, values) -> {
            throw new MaskingException(
                    type.getName(),
                    "cannot rebuild a masked copy because " + reason
                            + ". Use a record, add a no-argument or all-arguments constructor, "
                            + "or mask at serialisation time with the Jackson module instead",
                    null);
        };
    }
}
