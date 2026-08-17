package ch.raph.datamask.processor;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Types;

/**
 * Checks that a class holding {@code @PII} members can be rebuilt once those members are masked.
 *
 * <p>Masking produces a copy, so a class with something to mask needs a constructor the engine can
 * call: the canonical one if it is a record, otherwise an all-arguments constructor matching the
 * field order — what Lombok's {@code @AllArgsConstructor} and Jackson's {@code @ConstructorProperties}
 * emit — or a no-argument constructor it can follow with field writes. With neither, the first
 * attempt to mask an instance throws a {@code MaskingException}, and the diagnostic here names the
 * constructor that would have prevented it.
 *
 * <p>"Followed by field writes" is meant literally: the runtime writes each field with
 * {@code Lookup.unreflectSetter}, which refuses a final field. A class with a no-argument
 * constructor and final fields therefore reads as rebuildable and is not, which is why the two are
 * decided together here.
 *
 * <p>The check is deliberately local: it fires on a class that declares {@code @PII} itself, and
 * never tries to work out whether some type nested three levels down carries PII. Declared types
 * lie — {@code Object}, an interface, a subclass — and a guess in either direction would be worse
 * than the runtime's own answer.
 */
final class RebuildableTypeCheck {

    private final Types types;
    private final Reporter reporter;

    RebuildableTypeCheck(Types types, Reporter reporter) {
        this.types = types;
        this.reporter = reporter;
    }

    void check(TypeElement type) {
        // Records rebuild through their canonical constructor, which always exists. Enums,
        // interfaces and annotation types are never taken apart by the engine.
        if (type.getKind() != ElementKind.CLASS) {
            return;
        }
        // The engine only ever rebuilds a runtime class, which is a concrete subclass of this one,
        // and that subclass is where the constructor has to be.
        if (type.getModifiers().contains(Modifier.ABSTRACT)) {
            return;
        }
        if (type.getNestingKind() == NestingKind.LOCAL || type.getNestingKind() == NestingKind.ANONYMOUS) {
            return;
        }
        // Lombok generates constructors during annotation processing, so whether they are already in
        // the element model when this runs is not something to depend on. Say nothing rather than
        // report a class that is in fact rebuildable.
        if (hasLombokAnnotation(type)) {
            return;
        }

        if (type.getNestingKind() == NestingKind.MEMBER && !type.getModifiers().contains(Modifier.STATIC)) {
            reporter.error(
                    type,
                    type.getQualifiedName() + " holds @PII but is an inner class: every one of its "
                            + "constructors takes the enclosing instance, so the engine cannot rebuild a masked "
                            + "copy of it. Make it static, or make it a record.");
            return;
        }

        List<VariableElement> fields = instanceFields(type);
        if (fields.isEmpty()) {
            // No fields means no masked copy to build — nothing can change.
            return;
        }
        List<ExecutableElement> constructors = ElementFilter.constructorsIn(type.getEnclosedElements());
        if (constructors.stream().anyMatch(constructor -> matchesTheFields(constructor, fields))) {
            return;
        }

        // No declared constructor at all means the implicit no-argument one, which the engine reaches
        // exactly as it reaches a declared one.
        boolean noArguments = constructors.isEmpty()
                || constructors.stream()
                        .anyMatch(constructor -> constructor.getParameters().isEmpty());
        List<VariableElement> unwritable = finalFields(fields);
        if (noArguments && unwritable.isEmpty()) {
            return;
        }

        String allArguments = "a constructor " + type.getSimpleName() + "(" + signature(fields) + ") matching the "
                + "field order (" + names(fields) + ")";
        if (noArguments) {
            reporter.error(
                    type,
                    type.getQualifiedName() + " holds @PII but cannot be rebuilt once its values are masked: "
                            + "the engine follows a no-argument constructor with a write per field, and "
                            + names(unwritable) + (unwritable.size() == 1 ? " is final" : " are final")
                            + ", which a field write cannot set. Add " + allArguments
                            + ", make it a record, or drop the final.");
            return;
        }
        reporter.error(
                type,
                type.getQualifiedName() + " holds @PII but cannot be rebuilt once its values are masked: "
                        + "it has no no-argument constructor and no " + allArguments
                        + ". Add one of the two, make it a record, or mask it at "
                        + "serialisation time with datamask-jackson.");
    }

    /**
     * Whether the engine can rebuild through this constructor, which is only ever true of the
     * all-arguments shape — the no-argument one is decided separately, because it also needs every
     * field to be writable afterwards.
     *
     * <p>Two matchings, because {@code ReflectiveMaskPlanCompiler.fieldOrderFor} accepts two. In
     * declaration order the parameter types alone are enough. Out of order, the runtime matches by
     * name using {@code -parameters} or {@code @ConstructorProperties} and permutes the values, so
     * a constructor whose parameters name the fields counts as well — reporting one of those would
     * fail a build over code that masks perfectly.
     *
     * <p>Visibility is not part of it: the engine looks the type up with a private lookup, which
     * reaches a private constructor on the class path.
     */
    private boolean matchesTheFields(ExecutableElement constructor, List<VariableElement> fields) {
        List<? extends VariableElement> parameters = constructor.getParameters();
        if (parameters.isEmpty() || parameters.size() != fields.size()) {
            return false;
        }
        return matchesInOrder(parameters, fields) || matchesByName(parameters, fields);
    }

    private boolean matchesInOrder(List<? extends VariableElement> parameters, List<VariableElement> fields) {
        for (int i = 0; i < fields.size(); i++) {
            if (!isSameErasure(parameters.get(i), fields.get(i))) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesByName(List<? extends VariableElement> parameters, List<VariableElement> fields) {
        for (VariableElement parameter : parameters) {
            boolean matched = fields.stream()
                    .anyMatch(field -> field.getSimpleName().contentEquals(parameter.getSimpleName())
                            && isSameErasure(parameter, field));
            if (!matched) {
                return false;
            }
        }
        return parameters.stream()
                        .map(parameter -> parameter.getSimpleName().toString())
                        .distinct()
                        .count()
                == parameters.size();
    }

    private boolean isSameErasure(VariableElement parameter, VariableElement field) {
        return types.isSameType(types.erasure(parameter.asType()), types.erasure(field.asType()));
    }

    /**
     * The fields a no-argument constructor could not be followed by a write to.
     *
     * <p>The runtime writes them with {@code Lookup.unreflectSetter}, which refuses a final field
     * outright — a private lookup does not help, and neither does a setter, because a setter cannot
     * assign one either. So a class whose only constructor takes no arguments and whose fields are
     * final is unrebuildable, however ordinary it looks, and every instance of it fails on the first
     * mask.
     */
    private static List<VariableElement> finalFields(List<VariableElement> fields) {
        return fields.stream()
                .filter(field -> field.getModifiers().contains(Modifier.FINAL))
                .toList();
    }

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

    private TypeElement superclassOf(TypeElement type) {
        TypeMirror superclass = type.getSuperclass();
        if (superclass.getKind() != TypeKind.DECLARED) {
            return null;
        }
        Element element = ((DeclaredType) superclass).asElement();
        if (!(element instanceof TypeElement parent)
                || parent.getQualifiedName().contentEquals(Object.class.getCanonicalName())) {
            return null;
        }
        return parent;
    }

    private String signature(List<VariableElement> fields) {
        return fields.stream().map(field -> simpleNameOf(field.asType())).collect(Collectors.joining(", "));
    }

    private static String names(List<VariableElement> fields) {
        return fields.stream().map(field -> field.getSimpleName().toString()).collect(Collectors.joining(", "));
    }

    private String simpleNameOf(TypeMirror type) {
        TypeMirror erased = types.erasure(type);
        Element element = types.asElement(erased);
        return element == null ? erased.toString() : element.getSimpleName().toString();
    }

    private static boolean hasLombokAnnotation(TypeElement type) {
        return type.getAnnotationMirrors().stream()
                .map(mirror -> ((TypeElement) mirror.getAnnotationType().asElement()).getQualifiedName())
                .anyMatch(name -> name.toString().startsWith("lombok."));
    }
}
