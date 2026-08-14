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
        if (constructors.isEmpty() || constructors.stream().anyMatch(constructor -> isUsable(constructor, fields))) {
            return;
        }

        reporter.error(
                type,
                type.getQualifiedName() + " holds @PII but cannot be rebuilt once its values are masked: "
                        + "it has no no-argument constructor and no constructor "
                        + type.getSimpleName() + "(" + signature(fields) + ") matching the field order ("
                        + names(fields) + "). Add one of the two, make it a record, or mask it at "
                        + "serialisation time with datamask-jackson.");
    }

    /**
     * A constructor the engine can rebuild through: the no-argument one, or one whose parameters are
     * exactly the instance fields in order. Visibility is not part of it — the engine looks the type
     * up with a private lookup, which reaches a private constructor on the class path.
     */
    private boolean isUsable(ExecutableElement constructor, List<VariableElement> fields) {
        List<? extends VariableElement> parameters = constructor.getParameters();
        if (parameters.isEmpty()) {
            return true;
        }
        if (parameters.size() != fields.size()) {
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
