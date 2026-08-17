package ch.raph.datamask.processor;

import ch.raph.datamask.api.NoMask;
import ch.raph.datamask.api.PII;
import java.lang.annotation.Annotation;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;

/**
 * Validates {@code @PII} and {@code @NoMask} usage while the code is being compiled.
 *
 * <p>Everything checked here is otherwise found at runtime, in the shape of a masking exception on
 * whichever request happened to carry the annotated field — or, worse, not found at all, because
 * the runtime quietly corrects the declaration and the code keeps claiming something else. Add the
 * module to the annotation processor path and the same mistakes stop the build instead:
 *
 * {@snippet lang = groovy:
 * annotationProcessor 'ch.raph.datamask:datamask-check-processor'
 * }
 *
 * <p>Findings that fail at runtime are errors; findings the runtime corrects for itself are
 * warnings. {@code -Adatamask.strict=false} turns the errors into warnings too, which is how an
 * existing codebase adopts the processor without the first build stopping on the first field.
 */
@SupportedOptions(PiiProcessor.STRICT_OPTION)
public final class PiiProcessor extends AbstractProcessor {

    static final String STRICT_OPTION = "datamask.strict";

    private CustomMaskerCheck customMasker;
    private PartialRevealCheck partialReveal;
    private JustificationCheck justification;
    private RebuildableTypeCheck rebuildable;
    private IneffectivePiiCheck ineffective;

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of(PII.class.getCanonicalName(), NoMask.class.getCanonicalName());
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public synchronized void init(ProcessingEnvironment environment) {
        super.init(environment);
        var types = environment.getTypeUtils();
        Reporter reporter = new Reporter(environment.getMessager(), isStrict(environment));
        this.customMasker = new CustomMaskerCheck(types, reporter);
        this.partialReveal = new PartialRevealCheck(reporter);
        this.justification = new JustificationCheck(reporter);
        this.rebuildable = new RebuildableTypeCheck(types, reporter);
        this.ineffective = new IneffectivePiiCheck(reporter);
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        if (round.processingOver()) {
            return false;
        }
        try {
            Set<TypeElement> owners = new LinkedHashSet<>();
            for (Element element : declarationsAnnotatedWith(round, PII.class)) {
                AnnotationSite.of(element, PII.class).ifPresent(site -> {
                    customMasker.check(site);
                    partialReveal.check(site, element.getAnnotation(PII.class));
                    ineffective.check(site);
                });
                ownerOf(element).ifPresent(owners::add);
            }
            for (Element element : declarationsAnnotatedWith(round, NoMask.class)) {
                AnnotationSite.of(element, NoMask.class)
                        .ifPresent(site -> justification.check(site, element.getAnnotation(NoMask.class)));
            }
            owners.forEach(rebuildable::check);
        } catch (RuntimeException failure) {
            // A bug in a validator must not be the reason a build cannot be run at all. Reported as
            // a warning naming the failure, never swallowed, so it does not read as a clean check.
            processingEnv
                    .getMessager()
                    .printMessage(
                            Diagnostic.Kind.WARNING,
                            "datamask: @PII validation was skipped after "
                                    + failure.getClass().getName() + ": " + failure.getMessage());
        }
        // Deliberately not claimed: another processor may want to read the same annotations — a
        // compliance report generated from @PII is the obvious next one.
        return false;
    }

    /**
     * The declarations worth checking, once each.
     *
     * <p>javac copies an annotation on a record component onto the field, the accessor and the
     * canonical constructor parameter, so the same {@code @PII} arrives here up to four times.
     * Reporting on the record component alone keeps one finding per line of source. Constructor
     * parameters are skipped outright: the engine reads fields and record components, never
     * parameters.
     */
    private static List<Element> declarationsAnnotatedWith(
            RoundEnvironment round, Class<? extends Annotation> annotation) {
        return round.getElementsAnnotatedWith(annotation).stream()
                .filter(element -> isDeclarationSite(element, annotation))
                .map(Element.class::cast)
                .toList();
    }

    private static boolean isDeclarationSite(Element element, Class<? extends Annotation> annotation) {
        return switch (element.getKind()) {
            case PARAMETER -> false;
            case FIELD, METHOD -> !isRecordComponentCopy(element, annotation);
            default -> true;
        };
    }

    /**
     * Whether javac put <em>this</em> annotation here itself, by copying it off the record component
     * of the same name.
     *
     * <p>The annotation has to be on the component for that to be what happened. A hand-written
     * accessor carrying an annotation its component does not have was written by someone on purpose,
     * and treating it as a copy meant the checks never ran on it — a {@code @NoMask} with a blank
     * justification on such an accessor went through in silence, which is the one exemption in this
     * library that must not.
     */
    private static boolean isRecordComponentCopy(Element element, Class<? extends Annotation> annotation) {
        return element.getEnclosingElement() instanceof TypeElement owner
                && owner.getKind() == ElementKind.RECORD
                && ElementFilter.recordComponentsIn(owner.getEnclosedElements()).stream()
                        .filter(component -> component.getSimpleName().contentEquals(element.getSimpleName()))
                        .anyMatch(component -> component.getAnnotation(annotation) != null);
    }

    /**
     * The type that has to be rebuilt when this member is masked. A type-level {@code @PII} has no
     * owner in that sense: it declares what values of the type are, and the engine masks such a
     * value whole rather than taking it apart.
     */
    private static Optional<TypeElement> ownerOf(Element element) {
        return switch (element.getKind()) {
            case FIELD, RECORD_COMPONENT, METHOD ->
                element.getEnclosingElement() instanceof TypeElement owner ? Optional.of(owner) : Optional.empty();
            default -> Optional.empty();
        };
    }

    private static boolean isStrict(ProcessingEnvironment environment) {
        String configured = environment.getOptions().get(STRICT_OPTION);
        return configured == null || !configured.equalsIgnoreCase("false");
    }
}
