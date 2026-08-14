package ch.raph.datamask.processor.plan;

import ch.raph.datamask.api.NoMask;
import ch.raph.datamask.api.PII;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;

/**
 * Generates a masking plan per type at compile time, so the engine does not have to derive one by
 * reflection on the first request that carries the type.
 *
 * {@snippet lang = groovy:
 * annotationProcessor 'ch.raph.datamask:datamask-build-processor'
 * }
 *
 * <p>That line is the whole wiring. Each plan is emitted next to the type it describes and listed in
 * {@code META-INF/services}, and {@code GeneratedMaskPlanCompiler} — which {@code DataMask.builder()}
 * already uses — picks them up from there. Nothing in the application changes, and a type this
 * processor did not cover still gets its plan reflectively.
 *
 * <h2>Which types get one</h2>
 *
 * Every type in the compilation that declares {@code @PII} or {@code @NoMask}, and every type that
 * holds one — transitively, through fields, record components, arrays and generic type arguments.
 * That last part is what covers a wrapper such as {@code Portfolio}, which declares nothing itself
 * but is the object the engine actually walks.
 *
 * <p>A type whose plan cannot be written without reflection is left alone rather than reported: a
 * private field with no accessor, a class with no constructor a sibling class could call. The
 * reflective compiler handles those, {@code -Adatamask.plan.verbose=true} names them, and
 * {@code datamask-check-processor} is what has an opinion about whether a type carrying {@code @PII}
 * ought to be shaped that way at all.
 */
@SupportedOptions(MaskPlanProcessor.VERBOSE_OPTION)
public final class MaskPlanProcessor extends AbstractProcessor {

    static final String VERBOSE_OPTION = "datamask.plan.verbose";

    private static final String SERVICE_FILE = "META-INF/services/" + Names.GENERATED_MASK_PLAN;

    /** Generated class names, accumulated across rounds and written out at the end as one service file. */
    private final Set<String> generated = new TreeSet<>();

    private PlanAnalyzer analyzer;
    private boolean verbose;

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
        this.analyzer = new PlanAnalyzer(environment.getTypeUtils(), environment.getElementUtils());
        this.verbose = Boolean.parseBoolean(environment.getOptions().get(VERBOSE_OPTION));
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        try {
            if (round.processingOver()) {
                writeServiceFile();
            } else {
                generateFor(round);
            }
        } catch (RuntimeException failure) {
            // Generation is an optimisation. A bug in it must not be the reason a build cannot be
            // run at all, and every type it did not reach still gets a reflective plan — so this is
            // a warning naming the failure, never a swallowed exception that reads as success.
            warn("plan generation was skipped after " + failure.getClass().getName() + ": " + failure.getMessage());
        }
        // Deliberately not claimed: datamask-check-processor reads the same annotations, and a
        // compliance report generated from @PII is the obvious next one to.
        return false;
    }

    private void generateFor(RoundEnvironment round) {
        Map<String, TypeElement> candidates = new LinkedHashMap<>();
        for (Element root : round.getRootElements()) {
            collectTypes(root, candidates);
        }
        for (TypeElement type : typesWorthPlanning(candidates)) {
            String qualified = type.getQualifiedName().toString();
            if (!generated.contains(planNameOf(type))) {
                write(type, qualified);
            }
        }
    }

    private void write(TypeElement type, String qualified) {
        switch (analyzer.analyze(type)) {
            case PlanAnalyzer.Analysis.Refused refused ->
                note(qualified + " " + refused.reason() + "; its plan will be derived reflectively on first use");
            case PlanAnalyzer.Analysis.Planned planned -> {
                String name = planNameOf(type);
                try {
                    JavaFileObject file = processingEnv.getFiler().createSourceFile(name, type);
                    try (Writer writer = file.openWriter()) {
                        writer.write(PlanWriter.write(planned.model()));
                    }
                    generated.add(name);
                } catch (IOException e) {
                    warn("could not write the mask plan for " + qualified + ": " + e.getMessage());
                }
            }
        }
    }

    /**
     * Everything the engine will walk: the types that declare {@code @PII} or {@code @NoMask}, plus
     * everything that holds one of those, to a fixed point.
     *
     * <p>The second half is what makes this worth doing. A wrapper such as {@code Portfolio} carries
     * no annotation of its own, but it is the object handed to {@code mask()} — leaving it out would
     * put reflection back on the first request for exactly the type an application masks most.
     */
    private List<TypeElement> typesWorthPlanning(Map<String, TypeElement> candidates) {
        Set<String> included = new LinkedHashSet<>();
        for (Map.Entry<String, TypeElement> candidate : candidates.entrySet()) {
            if (declaresMasking(candidate.getValue())) {
                included.add(candidate.getKey());
            }
        }

        boolean grew = true;
        while (grew) {
            grew = false;
            for (Map.Entry<String, TypeElement> candidate : candidates.entrySet()) {
                if (included.contains(candidate.getKey())) {
                    continue;
                }
                if (memberTypeNames(candidate.getValue()).stream().anyMatch(included::contains)) {
                    included.add(candidate.getKey());
                    grew = true;
                }
            }
        }

        return included.stream().map(candidates::get).toList();
    }

    private static boolean declaresMasking(TypeElement type) {
        if (type.getAnnotation(PII.class) != null) {
            return true;
        }
        // Fields, components and methods only. A nested type is an enclosed element too, and a
        // holder class full of annotated records would otherwise look annotated itself.
        return Stream.of(
                        ElementFilter.fieldsIn(type.getEnclosedElements()),
                        ElementFilter.recordComponentsIn(type.getEnclosedElements()),
                        ElementFilter.methodsIn(type.getEnclosedElements()))
                .flatMap(List::stream)
                .anyMatch(member ->
                        member.getAnnotation(PII.class) != null || member.getAnnotation(NoMask.class) != null);
    }

    /** Every declared type a field or record component of this type could hold, type arguments included. */
    private Set<String> memberTypeNames(TypeElement type) {
        Set<String> names = new LinkedHashSet<>();
        List<TypeMirror> members = new ArrayList<>();
        ElementFilter.fieldsIn(type.getEnclosedElements()).forEach(field -> members.add(field.asType()));
        ElementFilter.recordComponentsIn(type.getEnclosedElements())
                .forEach(component -> members.add(component.asType()));
        members.forEach(member -> collectNames(member, names));
        return names;
    }

    private void collectNames(TypeMirror type, Set<String> into) {
        switch (type.getKind()) {
            case ARRAY -> collectNames(((ArrayType) type).getComponentType(), into);
            case DECLARED -> {
                DeclaredType declared = (DeclaredType) type;
                if (declared.asElement() instanceof TypeElement element) {
                    into.add(element.getQualifiedName().toString());
                }
                declared.getTypeArguments().forEach(argument -> collectNames(argument, into));
            }
            default -> {
                // Primitives, type variables and wildcards hold nothing this processor can plan for.
            }
        }
    }

    private static void collectTypes(Element element, Map<String, TypeElement> into) {
        if (element instanceof TypeElement type
                && (type.getKind() == ElementKind.CLASS || type.getKind() == ElementKind.RECORD)) {
            into.put(type.getQualifiedName().toString(), type);
        }
        if (element instanceof TypeElement type) {
            ElementFilter.typesIn(type.getEnclosedElements()).forEach(nested -> collectTypes(nested, into));
        }
    }

    /**
     * One service file, written in the final round with everything seen.
     *
     * <p>{@code Filer} has no way to append to a resource, so it has to be written once and it has
     * to be complete — which is why the module declares itself an aggregating processor to Gradle.
     * An isolating one would be handed only the changed sources on an incremental build and would
     * rewrite this file with a fraction of the plans in it, silently returning the missing types to
     * reflection.
     */
    private void writeServiceFile() {
        if (generated.isEmpty()) {
            return;
        }
        try {
            Filer filer = processingEnv.getFiler();
            var resource = filer.createResource(StandardLocation.CLASS_OUTPUT, "", SERVICE_FILE);
            try (Writer writer = resource.openWriter()) {
                for (String name : generated) {
                    writer.write(name);
                    writer.write('\n');
                }
            }
            note("generated " + generated.size() + " mask plan" + (generated.size() == 1 ? "" : "s"));
        } catch (IOException e) {
            warn("could not write " + SERVICE_FILE + ": " + e.getMessage()
                    + ". Every generated plan is unreachable, and masking falls back to reflection.");
        }
    }

    private static String planNameOf(TypeElement type) {
        String packageName = PlanAnalyzer.packageOf(type);
        String simpleName = PlanAnalyzer.generatedNameOf(type);
        return packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
    }

    private void note(String message) {
        if (verbose) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "datamask: " + message);
        }
    }

    private void warn(String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "datamask: " + message);
    }
}
