package ch.raph.datamask.processor.plan;

import ch.raph.datamask.api.MaskStrategy;
import ch.raph.datamask.api.NoMask;
import ch.raph.datamask.api.PII;
import ch.raph.datamask.api.PiiCategory;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * Works out, for one type, whether a plan can be generated for it and what that plan says.
 *
 * <p>This is the compile-time counterpart of {@code ReflectiveMaskPlanCompiler}, and the whole
 * exercise turns on the two producing the same answer. Where the runtime reads a {@code Field} and
 * unreflects a {@code MethodHandle}, this reads a {@code VariableElement} and writes an expression;
 * where the runtime asks {@code privateLookupIn} for access, this asks whether the generated class —
 * which sits in the target's own package — could write that expression at all.
 *
 * <p>That last difference is the only reason a type is ever refused. A private lookup reaches a
 * private field; generated source cannot, and a getter is not the same thing, because a getter may
 * compute. So a type whose fields are private with no accessor, or that has no constructor the
 * generated class can call, comes back as {@link Analysis.Refused} and the reflective compiler keeps
 * it. Nothing is lost but the speed.
 */
final class PlanAnalyzer {

    private final Types types;
    private final LeafTypes leaves;

    PlanAnalyzer(Types types, Elements elements) {
        this.types = types;
        this.leaves = new LeafTypes(types, elements);
    }

    /** Either a plan to write, or why there is none. */
    sealed interface Analysis {

        record Planned(PlanModel model) implements Analysis {}

        /** The reflective compiler keeps this type. The reason is for {@code -Adatamask.plan.verbose}. */
        record Refused(String reason) implements Analysis {}
    }

    Analysis analyze(TypeElement type) {
        Optional<String> unusable = whyTypeIsUnusable(type);
        if (unusable.isPresent()) {
            return new Analysis.Refused(unusable.get());
        }
        return type.getKind() == ElementKind.RECORD ? analyzeRecord(type) : analyzeBean(type);
    }

    // --- records ------------------------------------------------------------------------------

    private Analysis analyzeRecord(TypeElement type) {
        List<RecordComponentElement> components = ElementFilter.recordComponentsIn(type.getEnclosedElements());
        if (components.isEmpty()) {
            return new Analysis.Refused("has no components, so there is nothing to mask");
        }

        String target = type.getQualifiedName().toString();
        List<PlanModel.Member> members = new ArrayList<>(components.size());
        List<String> parameterTypes = new ArrayList<>(components.size());
        for (RecordComponentElement component : components) {
            String declaredType = erasedName(component.asType());
            parameterTypes.add(declaredType);
            members.add(new PlanModel.Member(
                    component.getSimpleName().toString(),
                    declaredType,
                    "((" + target + ") target)." + component.getSimpleName() + "()",
                    actionFor(new Declaration(component, null), component.asType())));
        }
        return planned(type, members, new PlanModel.Rebuild.Constructor(parameterTypes));
    }

    // --- beans --------------------------------------------------------------------------------

    private Analysis analyzeBean(TypeElement type) {
        List<VariableElement> fields = instanceFields(type);
        if (fields.isEmpty()) {
            // The runtime calls this opaque and passes the value through; there is nothing to speed up.
            return new Analysis.Refused("has no instance fields, so the engine treats it as opaque");
        }

        String target = type.getQualifiedName().toString();
        String generatedPackage = packageOf(type);
        List<PlanModel.Member> members = new ArrayList<>(fields.size());
        for (VariableElement field : fields) {
            Optional<ExecutableElement> getter = getterFor(type, field);
            Optional<String> read = readExpression(field, getter, generatedPackage, target);
            if (read.isEmpty()) {
                return new Analysis.Refused("field '" + field.getSimpleName()
                        + "' is private and has no accessor the generated plan could call");
            }
            members.add(new PlanModel.Member(
                    field.getSimpleName().toString(),
                    erasedName(field.asType()),
                    read.get(),
                    actionFor(new Declaration(field, getter.orElse(null)), field.asType())));
        }

        return rebuildFor(type, fields, generatedPackage)
                .<Analysis>map(rebuild -> planned(type, members, rebuild))
                .orElseGet(() -> new Analysis.Refused("has no constructor the generated plan could call: neither one "
                        + "matching the field order nor a no-argument one with every field writable"));
    }

    /**
     * How the generated plan reads a field: the field itself when it can see it, and a getter only
     * when it cannot.
     *
     * <p>The field comes first because the field is what the runtime reads. A getter that computes
     * something rather than returning its field would make the two disagree, and only the field
     * cannot.
     */
    private Optional<String> readExpression(
            VariableElement field, Optional<ExecutableElement> getter, String generatedPackage, String target) {
        if (isAccessibleFrom(field, generatedPackage)) {
            return Optional.of("((" + target + ") target)." + field.getSimpleName());
        }
        return getter.filter(method -> isAccessibleFrom(method, generatedPackage))
                .map(method -> "((" + target + ") target)." + method.getSimpleName() + "()");
    }

    /**
     * Beans are rebuilt by an all-arguments constructor when one matches the field order — which is
     * what Lombok's {@code @AllArgsConstructor} and Jackson's {@code @ConstructorProperties} both
     * produce — and otherwise by a no-argument constructor plus a write per field.
     */
    private Optional<PlanModel.Rebuild> rebuildFor(
            TypeElement type, List<VariableElement> fields, String generatedPackage) {
        List<ExecutableElement> constructors = ElementFilter.constructorsIn(type.getEnclosedElements());

        boolean allArguments = constructors.stream()
                .anyMatch(constructor ->
                        matchesFieldOrder(constructor, fields) && isAccessibleFrom(constructor, generatedPackage));
        if (allArguments) {
            List<String> parameterTypes =
                    fields.stream().map(field -> erasedName(field.asType())).toList();
            return Optional.of(new PlanModel.Rebuild.Constructor(parameterTypes));
        }

        boolean noArguments = constructors.isEmpty()
                || constructors.stream()
                        .anyMatch(constructor -> constructor.getParameters().isEmpty()
                                && isAccessibleFrom(constructor, generatedPackage));
        if (!noArguments) {
            return Optional.empty();
        }

        List<PlanModel.Write> writes = new ArrayList<>(fields.size());
        for (VariableElement field : fields) {
            Optional<PlanModel.Write> write = writeFor(type, field, generatedPackage);
            if (write.isEmpty()) {
                return Optional.empty();
            }
            writes.add(write.get());
        }
        return Optional.of(new PlanModel.Rebuild.NoArgumentsThenWrite(writes));
    }

    private Optional<PlanModel.Write> writeFor(TypeElement owner, VariableElement field, String generatedPackage) {
        String declaredType = erasedName(field.asType());
        if (!field.getModifiers().contains(Modifier.FINAL) && isAccessibleFrom(field, generatedPackage)) {
            return Optional.of(new PlanModel.Write(field.getSimpleName().toString(), declaredType, false));
        }
        return setterFor(owner, field)
                .filter(setter -> isAccessibleFrom(setter, generatedPackage))
                .map(setter -> new PlanModel.Write(setter.getSimpleName().toString(), declaredType, true));
    }

    private boolean matchesFieldOrder(ExecutableElement constructor, List<VariableElement> fields) {
        List<? extends VariableElement> parameters = constructor.getParameters();
        if (parameters.isEmpty() || parameters.size() != fields.size()) {
            return false;
        }
        for (int i = 0; i < fields.size(); i++) {
            if (!types.isSameType(
                    types.erasure(parameters.get(i).asType()),
                    types.erasure(fields.get(i).asType()))) {
                return false;
            }
        }
        return true;
    }

    // --- actions ------------------------------------------------------------------------------

    /**
     * The resolution chain from {@code ReflectiveMaskPlanCompiler.actionFor}, minus the two steps
     * that consult {@code PolicyOverrides} — those exist only at runtime, which is why
     * {@code GeneratedMaskPlanCompiler} steps aside entirely once overrides are configured.
     *
     * <p>The order is the design and is copied deliberately: an exemption beats everything, a
     * {@code @PII} on the member beats one on its type unless the member's is a bare marker, and
     * unannotated text still descends so content scanning can look at it.
     */
    private PlanModel.Action actionFor(Declaration declaration, TypeMirror declaredType) {
        if (declaration.annotation(NoMask.class) != null) {
            return PlanModel.Action.KEEP;
        }

        PII onMember = declaration.annotation(PII.class);
        PII onType = typeLevelPii(declaredType);
        if (onMember != null) {
            PlanModel.Descriptor descriptor = descriptorOf(onMember);
            // A bare `@PII Email email` defers to whatever the Email type itself declares, which is
            // both more precise and cheaper than working the category out from the value's content.
            return new PlanModel.Action.Mask(isBare(descriptor) && onType != null ? descriptorOf(onType) : descriptor);
        }
        if (onType != null) {
            return new PlanModel.Action.Mask(descriptorOf(onType));
        }

        // Unannotated text still descends: the policy may have content scanning enabled, which is
        // how PII that nobody declared gets caught.
        if (leaves.isCharSequence(declaredType)) {
            return PlanModel.Action.DESCEND;
        }
        return leaves.isLeaf(declaredType) ? PlanModel.Action.KEEP : PlanModel.Action.DESCEND;
    }

    /**
     * One member's two possible annotation sites, in the order the runtime consults them: the field
     * or record component first, its getter second.
     *
     * <p>Each annotation is looked up on its own rather than one site being chosen for both, because
     * {@code @PII} on a field and {@code @NoMask} on its getter is a real combination and the runtime
     * finds both.
     */
    private record Declaration(Element primary, Element secondary) {

        <A extends Annotation> A annotation(Class<A> type) {
            A onPrimary = primary.getAnnotation(type);
            if (onPrimary != null) {
                return onPrimary;
            }
            return secondary == null ? null : secondary.getAnnotation(type);
        }
    }

    /** {@code @PII} on the declared type, following {@code @Inherited} up the superclass chain. */
    private PII typeLevelPii(TypeMirror declaredType) {
        TypeMirror erased = types.erasure(declaredType);
        if (erased.getKind() != TypeKind.DECLARED) {
            return null;
        }
        Element current = ((DeclaredType) erased).asElement();
        while (current instanceof TypeElement type) {
            PII declared = type.getAnnotation(PII.class);
            if (declared != null) {
                return declared;
            }
            TypeMirror superclass = type.getSuperclass();
            current = superclass.getKind() == TypeKind.DECLARED ? ((DeclaredType) superclass).asElement() : null;
        }
        return null;
    }

    private PlanModel.Descriptor descriptorOf(PII annotation) {
        return new PlanModel.Descriptor(
                Names.PII_CATEGORY + "." + annotation.category().name(),
                Names.SENSITIVITY + "." + annotation.sensitivity().name(),
                Names.MASK_STRATEGY + "." + annotation.strategy().name(),
                annotation.keep(),
                annotation.padding(),
                annotation.replacement(),
                maskerNameOf(annotation),
                annotation.purpose());
    }

    /**
     * A {@code Class}-valued annotation attribute cannot be read through the proxy — the class it
     * names may not be compiled yet — so the mirror the proxy throws is the value.
     */
    private String maskerNameOf(PII annotation) {
        try {
            return annotation.masker().getCanonicalName();
        } catch (MirroredTypeException mirrored) {
            TypeMirror erased = types.erasure(mirrored.getTypeMirror());
            if (erased.getKind() != TypeKind.DECLARED) {
                return Names.MASKER;
            }
            return ((TypeElement) ((DeclaredType) erased).asElement())
                    .getQualifiedName()
                    .toString();
        }
    }

    /** Whether the annotation adds nothing of its own and is purely a marker. */
    private static boolean isBare(PlanModel.Descriptor descriptor) {
        return descriptor.strategy().equals(Names.MASK_STRATEGY + "." + MaskStrategy.AUTO.name())
                && descriptor.category().equals(Names.PII_CATEGORY + "." + PiiCategory.UNSPECIFIED.name())
                && descriptor.maskerType().equals(Names.MASKER)
                && descriptor.replacement().isEmpty()
                && descriptor.keep() == -1;
    }

    // --- shapes the generated class cannot handle ----------------------------------------------

    private Optional<String> whyTypeIsUnusable(TypeElement type) {
        if (type.getKind() != ElementKind.CLASS && type.getKind() != ElementKind.RECORD) {
            return Optional.of("is not a class or a record, so the engine never takes it apart");
        }
        if (type.getModifiers().contains(Modifier.ABSTRACT)) {
            // The engine plans the runtime class, which is always a concrete subclass of this one.
            return Optional.of("is abstract, so no instance of it is ever masked");
        }
        if (type.getNestingKind() == NestingKind.LOCAL || type.getNestingKind() == NestingKind.ANONYMOUS) {
            return Optional.of("is local or anonymous, so it has no name to generate against");
        }
        if (type.getNestingKind() == NestingKind.MEMBER && !type.getModifiers().contains(Modifier.STATIC)) {
            return Optional.of("is an inner class, so every one of its constructors takes the enclosing instance");
        }
        if (!type.getTypeParameters().isEmpty()) {
            // A generated plan would have to name a raw type and rebuild through it. The runtime
            // already works on the raw class, so leaving generics to it keeps this side honest.
            return Optional.of("is generic, and a generated plan would have to rebuild through a raw type");
        }
        for (Element current = type;
                current instanceof TypeElement enclosing;
                current = enclosing.getEnclosingElement()) {
            if (enclosing.getModifiers().contains(Modifier.PRIVATE)) {
                return Optional.of("is private, so a generated class in the same package could not name it");
            }
        }
        return Optional.empty();
    }

    /**
     * Whether the generated class, which sits in {@code generatedPackage}, could write this member.
     *
     * <p>Protected counts as package access and nothing more: the generated plan is not a subclass,
     * so a protected member declared in another package stays out of reach.
     */
    private static boolean isAccessibleFrom(Element member, String generatedPackage) {
        if (member.getModifiers().contains(Modifier.PRIVATE)) {
            return false;
        }
        if (member.getModifiers().contains(Modifier.PUBLIC)) {
            return true;
        }
        return packageOf((TypeElement) member.getEnclosingElement()).equals(generatedPackage);
    }

    // --- element plumbing -----------------------------------------------------------------------

    /** Instance fields in the order the runtime reads them: this class first, then up the hierarchy. */
    private List<VariableElement> instanceFields(TypeElement type) {
        List<VariableElement> fields = new ArrayList<>();
        for (TypeElement current = type; current != null; current = superclassOf(current)) {
            for (VariableElement field : ElementFilter.fieldsIn(current.getEnclosedElements())) {
                if (!field.getModifiers().contains(Modifier.STATIC)) {
                    fields.add(field);
                }
            }
        }
        return fields;
    }

    private static TypeElement superclassOf(TypeElement type) {
        TypeMirror superclass = type.getSuperclass();
        if (superclass.getKind() != TypeKind.DECLARED) {
            return null;
        }
        Element element = ((DeclaredType) superclass).asElement();
        if (!(element instanceof TypeElement parent)
                || parent.getQualifiedName().contentEquals("java.lang.Object")) {
            return null;
        }
        return parent;
    }

    private Optional<ExecutableElement> getterFor(TypeElement owner, VariableElement field) {
        String name = field.getSimpleName().toString();
        String capitalised = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        for (String candidate : List.of("get" + capitalised, "is" + capitalised, name)) {
            Optional<ExecutableElement> found = methodsOf(owner)
                    .filter(method -> method.getSimpleName().contentEquals(candidate))
                    .filter(method -> method.getParameters().isEmpty())
                    .filter(method ->
                            types.isSameType(types.erasure(method.getReturnType()), types.erasure(field.asType())))
                    .findFirst();
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private Optional<ExecutableElement> setterFor(TypeElement owner, VariableElement field) {
        String name = field.getSimpleName().toString();
        String setter = "set" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
        return methodsOf(owner)
                .filter(method -> method.getSimpleName().contentEquals(setter))
                .filter(method -> method.getParameters().size() == 1)
                .filter(method -> types.isSameType(
                        types.erasure(method.getParameters().getFirst().asType()), types.erasure(field.asType())))
                .findFirst();
    }

    private static Stream<ExecutableElement> methodsOf(TypeElement owner) {
        List<ExecutableElement> methods = new ArrayList<>();
        for (TypeElement current = owner; current != null; current = superclassOf(current)) {
            methods.addAll(ElementFilter.methodsIn(current.getEnclosedElements()));
        }
        return methods.stream().filter(method -> !method.getModifiers().contains(Modifier.STATIC));
    }

    private Analysis planned(TypeElement type, List<PlanModel.Member> members, PlanModel.Rebuild rebuild) {
        return new Analysis.Planned(new PlanModel(
                packageOf(type), generatedNameOf(type), type.getQualifiedName().toString(), members, rebuild));
    }

    /** {@code Banking.Customer} becomes {@code Banking_Customer_MaskPlan}, in Banking's own package. */
    static String generatedNameOf(TypeElement type) {
        String qualified = type.getQualifiedName().toString();
        String packageName = packageOf(type);
        String nested = packageName.isEmpty() ? qualified : qualified.substring(packageName.length() + 1);
        return nested.replace('.', '_') + "_MaskPlan";
    }

    static String packageOf(TypeElement type) {
        Element current = type;
        while (current != null && current.getKind() != ElementKind.PACKAGE) {
            current = current.getEnclosingElement();
        }
        return current == null
                ? ""
                : ((PackageElement) current).getQualifiedName().toString();
    }

    private String erasedName(TypeMirror type) {
        return types.erasure(type).toString();
    }
}
