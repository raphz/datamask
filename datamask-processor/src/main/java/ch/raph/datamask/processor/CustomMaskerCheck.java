package ch.raph.datamask.processor;

import ch.raph.datamask.api.Masker;
import java.util.List;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Types;

/**
 * Checks that {@code @PII(masker = X.class)} names a class the engine can actually instantiate.
 *
 * <p>The engine builds a custom masker with {@code X.class.getDeclaredConstructor().newInstance()},
 * so anything reflection refuses — an abstract class, a class the caller cannot see, an inner class
 * whose constructor secretly takes its enclosing instance — fails the first time the annotated field
 * is masked.
 *
 * <p>What makes it worth a compile error is how quietly it fails. The engine fails closed: under the
 * default failure mode the field comes out as the redaction placeholder, so the output still looks
 * masked and only {@code MaskingObserver.onFailure} ever mentions that the masker the developer
 * wrote has never once run. Under {@code FailureMode.THROW} it surfaces as a {@code MaskingException}
 * instead — in production, on whichever request happened to carry the field.
 */
final class CustomMaskerCheck {

    private final Types types;
    private final Reporter reporter;

    CustomMaskerCheck(Types types, Reporter reporter) {
        this.types = types;
        this.reporter = reporter;
    }

    void check(AnnotationSite site) {
        AnnotationValue declared = site.attribute("masker");
        if (declared == null || !(declared.getValue() instanceof TypeMirror mirror)) {
            return;
        }
        if (!(types.asElement(mirror) instanceof TypeElement masker)) {
            return;
        }
        // `masker = Masker.class` is the annotation's own default written out: no custom masker.
        if (masker.getQualifiedName().contentEquals(Masker.class.getCanonicalName())) {
            return;
        }

        String name = masker.getQualifiedName().toString();
        String orRegister =
                " Or register the instance with DataMask.builder().masker(new " + masker.getSimpleName() + "(...)).";

        if (masker.getKind().isInterface()) {
            report(site, name + " is an interface and cannot be instantiated. Name a concrete Masker." + orRegister);
            return;
        }
        if (masker.getModifiers().contains(Modifier.ABSTRACT)) {
            report(site, name + " is abstract and cannot be instantiated. Name a concrete Masker." + orRegister);
            return;
        }
        if (masker.getNestingKind() == NestingKind.MEMBER
                && !masker.getModifiers().contains(Modifier.STATIC)) {
            report(
                    site,
                    name + " is an inner class, so its constructor takes the enclosing instance and the engine "
                            + "cannot call it. Make it static." + orRegister);
            return;
        }
        if (!isPubliclyVisible(masker)) {
            report(
                    site,
                    name + " is not visible to the engine, which instantiates a masker named by an annotation "
                            + "reflectively: the class and every type enclosing it must be public." + orRegister);
            return;
        }

        List<ExecutableElement> constructors = ElementFilter.constructorsIn(masker.getEnclosedElements());
        if (constructors.isEmpty()) {
            // No declared constructor means the implicit one, which is public because the class is.
            return;
        }
        ExecutableElement noArguments = constructors.stream()
                .filter(constructor -> constructor.getParameters().isEmpty())
                .findFirst()
                .orElse(null);
        if (noArguments == null) {
            report(site, name + " has no no-argument constructor. Add one." + orRegister);
            return;
        }
        if (!noArguments.getModifiers().contains(Modifier.PUBLIC)) {
            report(
                    site,
                    "the no-argument constructor of " + name + " is not public, and the engine instantiates a "
                            + "masker reflectively. Make it public." + orRegister);
        }
    }

    private void report(AnnotationSite site, String problem) {
        reporter.error(site, "masker", "@PII on " + site.path() + ": " + problem);
    }

    private static boolean isPubliclyVisible(TypeElement masker) {
        for (Element current = masker; current instanceof TypeElement type; current = current.getEnclosingElement()) {
            if (!type.getModifiers().contains(Modifier.PUBLIC)) {
                return false;
            }
        }
        return true;
    }
}
