package ch.raph.datamask.processor;

import java.lang.annotation.Annotation;
import java.util.Map;
import java.util.Optional;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;

/**
 * One annotated declaration, together with the mirror of the annotation that put it there.
 *
 * <p>The mirror is kept because it is what lets a diagnostic point at the offending attribute
 * rather than at the whole member — {@code keep = 3} rather than the field it sits on.
 */
record AnnotationSite(Element element, AnnotationMirror mirror) {

    static Optional<AnnotationSite> of(Element element, Class<? extends Annotation> annotation) {
        String wanted = annotation.getCanonicalName();
        return element.getAnnotationMirrors().stream()
                .filter(mirror -> qualifiedNameOf(mirror).contentEquals(wanted))
                .findFirst()
                .map(mirror -> new AnnotationSite(element, mirror));
    }

    /**
     * The value written for an attribute, or {@code null} when the declaration left it at its
     * default — the mirror only carries what the source actually says.
     */
    AnnotationValue attribute(String name) {
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry :
                mirror.getElementValues().entrySet()) {
            if (entry.getKey().getSimpleName().contentEquals(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Where the declaration is, in the same {@code Owner.member} shape the runtime uses for masking
     * paths — so a compile diagnostic and a masking exception name the same thing.
     */
    String path() {
        if (element instanceof TypeElement type) {
            return type.getQualifiedName().toString();
        }
        Element owner = element.getEnclosingElement();
        return owner == null
                ? element.getSimpleName().toString()
                : owner.getSimpleName() + "." + element.getSimpleName();
    }

    private static Name qualifiedNameOf(AnnotationMirror mirror) {
        return ((TypeElement) mirror.getAnnotationType().asElement()).getQualifiedName();
    }
}
