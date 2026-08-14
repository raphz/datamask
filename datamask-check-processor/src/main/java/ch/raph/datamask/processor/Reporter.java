package ch.raph.datamask.processor;

import javax.annotation.processing.Messager;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;

/**
 * Emits the processor's diagnostics.
 *
 * <p>Two rules are enforced here rather than left to each check. A diagnostic names the path and
 * the type and never a value, the same rule {@code MaskingException} follows — at compile time
 * there is no value to leak, but the habit is what keeps it that way when checks grow. And an
 * error is downgradable to a warning through {@code -Adatamask.strict=false}, so a codebase can
 * adopt the processor, see what it says, and fix the findings without the build stopping first.
 */
final class Reporter {

    private static final String PREFIX = "datamask: ";

    private final Messager messager;
    private final boolean strict;

    Reporter(Messager messager, boolean strict) {
        this.messager = messager;
        this.strict = strict;
    }

    /** A mistake that fails at runtime. Reported on the attribute that causes it when there is one. */
    void error(AnnotationSite site, String attribute, String message) {
        print(strict ? Diagnostic.Kind.ERROR : Diagnostic.Kind.WARNING, site, attribute, message);
    }

    /** A mistake the runtime corrects for itself, so the code lies about what it does. */
    void warning(AnnotationSite site, String attribute, String message) {
        print(Diagnostic.Kind.WARNING, site, attribute, message);
    }

    /** For findings about a declaration as a whole, where no single attribute is at fault. */
    void error(Element element, String message) {
        messager.printMessage(strict ? Diagnostic.Kind.ERROR : Diagnostic.Kind.WARNING, PREFIX + message, element);
    }

    private void print(Diagnostic.Kind kind, AnnotationSite site, String attribute, String message) {
        AnnotationValue value = attribute == null ? null : site.attribute(attribute);
        if (value == null) {
            messager.printMessage(kind, PREFIX + message, site.element(), site.mirror());
        } else {
            messager.printMessage(kind, PREFIX + message, site.element(), site.mirror(), value);
        }
    }
}
