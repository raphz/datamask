package ch.raph.datamask.processor;

import ch.raph.datamask.api.NoMask;
import java.util.List;
import java.util.Optional;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;

/**
 * Checks for a {@code @PII} that does not mask the value it is written on.
 *
 * <p>Both findings here are the same shape of mistake, and it is the worst one this library has: an
 * annotation that reads as protection and provides none. Nothing fails, nothing is logged, and every
 * test asserting "the raw value is absent" from some <em>other</em> field still passes. The only way
 * to notice is to already know the rule.
 *
 * <ul>
 *   <li><b>{@code @PII} and {@code @NoMask} on the same member</b> — the exemption is consulted first
 *       by both plan compilers, so the value is copied across in clear text. An error: the source
 *       says the value is masked and it is not.
 *   <li><b>{@code @PII} on a static field</b> — neither compiler ever looks at a static member, so
 *       the annotation is dead. A warning rather than an error: nothing is disclosed that was not
 *       already outside the object graph, but the declaration is a lie a reviewer will believe.
 * </ul>
 *
 * <p>A member's two annotation sites are consulted the way the runtime consults them: the field or
 * record component, then its getter. {@code @PII} on a field with {@code @NoMask} on its getter is
 * the same finding, and it is the harder one to see in a review.
 */
final class IneffectivePiiCheck {

    private final Reporter reporter;

    IneffectivePiiCheck(Reporter reporter) {
        this.reporter = reporter;
    }

    void check(AnnotationSite site) {
        exemptedByNoMask(site);
        declaredOnAStaticField(site);
    }

    private void exemptedByNoMask(AnnotationSite site) {
        Optional<Element> exemption = sitesOf(site.element()).stream()
                .filter(candidate -> candidate.getAnnotation(NoMask.class) != null)
                .findFirst();
        if (exemption.isEmpty()) {
            return;
        }
        String where = exemption.get().equals(site.element())
                ? "the same declaration"
                : exemption.get().getSimpleName() + "()";
        reporter.error(
                site,
                null,
                "@PII on " + site.path() + ": " + where + " also carries @NoMask, and the exemption wins — "
                        + "both plan compilers consult it before anything else, so the value is copied across in "
                        + "clear text. The declaration reads as protection and removes it. Remove one of the two.");
    }

    private void declaredOnAStaticField(AnnotationSite site) {
        Element element = site.element();
        if (element.getKind() != ElementKind.FIELD || !element.getModifiers().contains(Modifier.STATIC)) {
            return;
        }
        reporter.warning(
                site,
                null,
                "@PII on " + site.path() + ": the field is static, and both plan compilers read instance "
                        + "members only — a static field is never part of an instance's masked copy. The annotation "
                        + "does nothing. Make the field an instance field, or remove the annotation.");
    }

    /**
     * The declarations the runtime reads for one member: the annotated element itself, plus the
     * other half of a field-and-getter pair.
     *
     * <p>Matching is by name and takes no view of the return type, because that is exactly what
     * {@code ReflectiveMaskPlanCompiler.getterFor} does — it asks the class for a public no-argument
     * method by name and stops at the first that answers.
     */
    private static List<Element> sitesOf(Element member) {
        if (!(member.getEnclosingElement() instanceof TypeElement owner)) {
            return List.of(member);
        }
        return switch (member.getKind()) {
            case FIELD, RECORD_COMPONENT -> withGetter(owner, member);
            case METHOD -> withField(owner, member);
            default -> List.of(member);
        };
    }

    private static List<Element> withGetter(TypeElement owner, Element field) {
        String name = field.getSimpleName().toString();
        String capitalised = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        List<String> candidates = List.of("get" + capitalised, "is" + capitalised, name);
        return ElementFilter.methodsIn(owner.getEnclosedElements()).stream()
                .filter(method -> method.getParameters().isEmpty())
                .filter(method -> method.getModifiers().contains(Modifier.PUBLIC))
                .filter(method -> candidates.stream().anyMatch(method.getSimpleName()::contentEquals))
                .findFirst()
                .map(getter -> List.of(field, (Element) getter))
                .orElse(List.of(field));
    }

    private static List<Element> withField(TypeElement owner, Element method) {
        if (!(method instanceof ExecutableElement accessor) || !accessor.getParameters().isEmpty()) {
            return List.of(method);
        }
        String property = propertyNameOf(accessor.getSimpleName().toString());
        return ElementFilter.fieldsIn(owner.getEnclosedElements()).stream()
                .filter(field -> field.getSimpleName().contentEquals(property))
                .findFirst()
                .map(field -> List.of(method, (Element) field))
                .orElse(List.of(method));
    }

    private static String propertyNameOf(String accessor) {
        for (String prefix : List.of("get", "is")) {
            if (accessor.length() > prefix.length() && accessor.startsWith(prefix)) {
                String rest = accessor.substring(prefix.length());
                return Character.toLowerCase(rest.charAt(0)) + rest.substring(1);
            }
        }
        return accessor;
    }
}
