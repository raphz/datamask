package ch.raph.datamask.processor.plan;

import java.util.Set;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * Decides what the engine takes apart and what it treats as one value — the compile-time twin of
 * {@code ch.raph.datamask.infrastructure.reflect.Types}.
 *
 * <p>Two implementations of the same rule is a liability, and this one is deliberate: the runtime
 * version answers about a {@code Class}, this one about a {@code TypeMirror}, and neither can be
 * expressed in the other's world. What keeps them from drifting is not care, it is
 * {@code PlanEquivalenceTest}, which runs both compilers over the same domain and compares every
 * member's action — a disagreement here shows up there as a failed assertion rather than as a field
 * that stopped being masked.
 *
 * <p>The order of the checks is copied from the runtime version and matters: a container is asked
 * about before {@code CharSequence} and friends, and an interface is only "not a leaf" once the
 * assignability checks above it have had their say.
 */
final class LeafTypes {

    private static final Set<String> LEAF_TYPES = Set.of(
            "java.lang.String",
            "java.lang.Boolean",
            "java.lang.Byte",
            "java.lang.Character",
            "java.lang.Short",
            "java.lang.Integer",
            "java.lang.Long",
            "java.lang.Float",
            "java.lang.Double",
            "java.math.BigDecimal",
            "java.math.BigInteger",
            "java.util.UUID",
            "java.net.URI",
            "java.net.URL",
            "java.util.Locale",
            "java.util.Currency",
            "java.util.Date",
            "java.lang.Class",
            "java.lang.StringBuilder",
            "java.lang.StringBuffer");

    private static final Set<String> OPTIONALS =
            Set.of("java.util.Optional", "java.util.OptionalInt", "java.util.OptionalLong", "java.util.OptionalDouble");

    private static final Set<String> ATOMIC_SUPERTYPES = Set.of(
            "java.time.temporal.Temporal",
            "java.time.temporal.TemporalAmount",
            "java.util.Date",
            "java.lang.Number",
            "java.lang.CharSequence");

    private final Types types;
    private final Elements elements;

    LeafTypes(Types types, Elements elements) {
        this.types = types;
        this.elements = elements;
    }

    /** Whether a value of this type is atomic as far as masking is concerned. */
    boolean isLeaf(TypeMirror type) {
        // Object and bare interfaces say nothing about what they hold at runtime, so the engine
        // must look at the actual instance rather than stop here.
        if (isNamed(type, "java.lang.Object")) {
            return false;
        }
        if (type.getKind().isPrimitive()) {
            return true;
        }
        if (type.getKind() != TypeKind.DECLARED) {
            // Arrays are containers; anything else that reaches here is not a type the engine sees.
            return false;
        }
        TypeElement element = (TypeElement) ((DeclaredType) type).asElement();
        String name = element.getQualifiedName().toString();

        if (element.getKind() == ElementKind.ENUM || LEAF_TYPES.contains(name)) {
            return true;
        }
        if (isContainer(type)) {
            return false;
        }
        for (String supertype : ATOMIC_SUPERTYPES) {
            if (isAssignableTo(type, supertype)) {
                return true;
            }
        }
        if (element.getKind().isInterface()) {
            return false;
        }
        return name.startsWith("java.")
                || name.startsWith("javax.")
                || name.startsWith("jdk.")
                || name.startsWith("sun.");
    }

    boolean isContainer(TypeMirror type) {
        if (type.getKind() == TypeKind.ARRAY) {
            return true;
        }
        if (type.getKind() != TypeKind.DECLARED) {
            return false;
        }
        String name = ((TypeElement) ((DeclaredType) type).asElement())
                .getQualifiedName()
                .toString();
        return OPTIONALS.contains(name)
                || isAssignableTo(type, "java.util.Collection")
                || isAssignableTo(type, "java.util.Map");
    }

    boolean isCharSequence(TypeMirror type) {
        return isAssignableTo(type, "java.lang.CharSequence");
    }

    /**
     * Erasure on both sides, because the runtime version compares raw {@code Class} objects:
     * {@code List<Account>} is a {@code Collection} there whatever its type argument says.
     */
    private boolean isAssignableTo(TypeMirror type, String supertype) {
        TypeElement element = elements.getTypeElement(supertype);
        if (element == null) {
            return false;
        }
        return types.isAssignable(types.erasure(type), types.erasure(element.asType()));
    }

    private static boolean isNamed(TypeMirror type, String name) {
        return type.getKind() == TypeKind.DECLARED
                && ((TypeElement) ((DeclaredType) type).asElement())
                        .getQualifiedName()
                        .contentEquals(name);
    }
}
