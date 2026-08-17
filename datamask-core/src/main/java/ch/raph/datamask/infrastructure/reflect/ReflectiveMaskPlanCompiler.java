package ch.raph.datamask.infrastructure.reflect;

import ch.raph.datamask.api.MaskStrategy;
import ch.raph.datamask.api.Masker;
import ch.raph.datamask.api.NoMask;
import ch.raph.datamask.api.PII;
import ch.raph.datamask.api.PiiCategory;
import ch.raph.datamask.api.Sensitivity;
import ch.raph.datamask.application.MaskPlanCompiler;
import ch.raph.datamask.domain.MaskAction;
import ch.raph.datamask.domain.MaskPlan;
import ch.raph.datamask.domain.MemberPlan;
import ch.raph.datamask.domain.PiiDescriptor;
import ch.raph.datamask.domain.PolicyOverrides;
import ch.raph.datamask.domain.ValueRebuilder;
import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
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
            // An inaccessible record — a private record in a module that is not open to us. Nothing
            // proved it carries no PII, so the plan is marked failed and the engine treats every
            // value of the type as a structural failure: redacted or thrown, never passed through.
            return MaskPlan.failed(type, "record components are not accessible (" + e.getMessage() + ")");
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
            return MaskPlan.failed(type, "fields are not accessible (" + e.getMessage() + ")");
        }

        return new MaskPlan(type, members, rebuilderFor(type, fields, lookup));
    }

    /**
     * Beans are rebuilt by an all-arguments constructor when one can be matched to the fields —
     * which is what Lombok's {@code @AllArgsConstructor} and Jackson's
     * {@code @ConstructorProperties} both produce — and otherwise by a no-argument constructor plus
     * field writes.
     */
    private ValueRebuilder rebuilderFor(Class<?> type, List<Field> fields, MethodHandles.Lookup lookup) {
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            int[] order = fieldOrderFor(constructor, fields);
            if (order == null) {
                continue;
            }
            try {
                MethodHandle handle =
                        lookup.unreflectConstructor(constructor).asSpreader(Object[].class, fields.size());
                if (isIdentity(order)) {
                    return (original, values) -> handle.invoke(values);
                }
                return (original, values) -> handle.invoke(permute(values, order));
            } catch (IllegalAccessException ignored) {
                // Try the next candidate.
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

    /**
     * Which field feeds each parameter of a candidate constructor, or {@code null} when the two
     * cannot be matched safely.
     *
     * <p>Matching on parameter types alone is only sound while the types are distinct. Two
     * same-typed parameters declared in a different order than the fields would compile, run, and
     * quietly swap two values in every masked copy — an account holder's name appearing under
     * someone else's reference, with nothing to notice it by. So when names are unavailable and a
     * type repeats, this refuses the constructor and lets the setter path take over.
     *
     * <p>Names come from {@code -parameters} or from {@code @ConstructorProperties}, which Lombok
     * and the Jackson tooling both emit. With names available the order is free: parameters are
     * matched to fields by name and the values are permuted at rebuild time.
     */
    private static int[] fieldOrderFor(Constructor<?> constructor, List<Field> fields) {
        Class<?>[] parameterTypes = constructor.getParameterTypes();
        if (parameterTypes.length != fields.size() || parameterTypes.length == 0) {
            return null;
        }
        List<String> names = parameterNames(constructor);
        if (names == null && hasRepeatedType(fields)) {
            return null;
        }

        int[] order = new int[parameterTypes.length];
        boolean[] taken = new boolean[fields.size()];
        for (int i = 0; i < parameterTypes.length; i++) {
            int match = -1;
            for (int f = 0; f < fields.size(); f++) {
                Field field = fields.get(f);
                if (taken[f] || field.getType() != parameterTypes[i]) {
                    continue;
                }
                if (names != null && !field.getName().equals(names.get(i))) {
                    continue;
                }
                match = f;
                break;
            }
            if (match < 0) {
                return null;
            }
            taken[match] = true;
            order[i] = match;
        }
        return order;
    }

    /**
     * Parameter names when the class carries them, {@code null} otherwise. {@code @ConstructorProperties}
     * is read by name rather than imported, because it lives in {@code java.desktop} — a module a
     * trimmed runtime image is entitled to leave out.
     */
    private static List<String> parameterNames(Constructor<?> constructor) {
        for (Annotation annotation : constructor.getAnnotations()) {
            if (!annotation.annotationType().getName().equals("java.beans.ConstructorProperties")) {
                continue;
            }
            try {
                Object value = annotation.annotationType().getMethod("value").invoke(annotation);
                if (value instanceof String[] declared && declared.length == constructor.getParameterCount()) {
                    return List.of(declared);
                }
            } catch (ReflectiveOperationException ignored) {
                // Fall through to the reflected parameter names.
            }
        }

        Parameter[] parameters = constructor.getParameters();
        if (parameters.length == 0 || !parameters[0].isNamePresent()) {
            return null;
        }
        return java.util.Arrays.stream(parameters).map(Parameter::getName).toList();
    }

    private static boolean hasRepeatedType(List<Field> fields) {
        return fields.stream().map(Field::getType).distinct().count() != fields.size();
    }

    private static boolean isIdentity(int[] order) {
        for (int i = 0; i < order.length; i++) {
            if (order[i] != i) {
                return false;
            }
        }
        return true;
    }

    private static Object[] permute(Object[] values, int[] order) {
        Object[] arguments = new Object[order.length];
        for (int i = 0; i < order.length; i++) {
            arguments[i] = values[order[i]];
        }
        return arguments;
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
            return new MaskAction.Mask(merge(annotation, onType));
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
        if (Types.isScannableText(declaredType)) {
            return MaskAction.DESCEND;
        }
        return Types.isLeaf(declaredType) ? MaskAction.KEEP : MaskAction.DESCEND;
    }

    /**
     * Combines the {@code @PII} on a member with the one on the member's own type, attribute by
     * attribute: whatever the member states explicitly wins, and everything it left at the default
     * is inherited from the type.
     *
     * <p>The merge is per attribute rather than all-or-nothing because the alternative loses data
     * silently in both directions. {@code @PII(sensitivity = LOW) Email email} used to discard the
     * member's sensitivity, and {@code @PII(keep = 2) Email email} used to discard the type's EMAIL
     * category and mask an address as an anonymous string — neither of which the author would ever
     * see, because both still produce a masked value.
     *
     * <p>"Explicit" is measured against the annotation defaults, which is the only signal the
     * reflection API offers: an attribute set to its default is indistinguishable from one left out.
     * Stating {@code sensitivity = HIGH} on the member therefore reads as unset — acceptable,
     * because HIGH is already what the type would have to override to matter.
     */
    private static PiiDescriptor merge(PII member, PII onType) {
        if (onType == null) {
            return PiiDescriptor.from(member);
        }
        return new PiiDescriptor(
                member.category() != PiiCategory.UNSPECIFIED ? member.category() : onType.category(),
                member.sensitivity() != Sensitivity.HIGH ? member.sensitivity() : onType.sensitivity(),
                member.strategy() != MaskStrategy.AUTO ? member.strategy() : onType.strategy(),
                member.keep() != -1 ? member.keep() : onType.keep(),
                member.padding() != '*' ? member.padding() : onType.padding(),
                !member.replacement().isEmpty() ? member.replacement() : onType.replacement(),
                member.masker() != Masker.class ? member.masker() : onType.masker(),
                !member.purpose().isEmpty() ? member.purpose() : onType.purpose());
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

    /**
     * A rebuilder for a type whose members are readable but that offers no way to build a copy. The
     * members stay in the plan, so a PII-free instance still passes through the no-change
     * short-circuit; only when something actually changed does this throw — a plain runtime
     * exception rather than a {@code MaskingException}, so the engine degrades it per the failure
     * policy (redact under REDACT, surface under THROW) instead of crashing the caller.
     */
    private static ValueRebuilder unrebuildableRebuilder(Class<?> type, String reason) {
        return (original, values) -> {
            throw new IllegalStateException("cannot rebuild a masked copy of " + type.getName() + " because " + reason
                    + ". Use a record, add a no-argument or all-arguments constructor, "
                    + "or mask at serialisation time with the Jackson module instead");
        };
    }
}
