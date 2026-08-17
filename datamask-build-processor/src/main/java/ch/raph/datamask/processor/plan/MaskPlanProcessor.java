package ch.raph.datamask.processor.plan;

import ch.raph.datamask.api.NoMask;
import ch.raph.datamask.api.PII;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

/**
 * Generates a masking plan per type at compile time, so the engine does not have to derive one by
 * reflection on the first request that carries the type.
 *
 * {@snippet lang = groovy:
 * annotationProcessor 'ch.raph.datamask:datamask-build-processor'
 * }
 *
 * <p>That line is the whole wiring. Each plan is emitted next to the type it describes and named
 * after it — {@code Customer} gets {@code Customer_MaskPlan} — and {@code GeneratedMaskPlanCompiler},
 * which {@code DataMask.builder()} already uses, looks it up by that name. Nothing in the application
 * changes, and a type this processor did not cover still gets its plan reflectively.
 *
 * <h2>Which types get one</h2>
 *
 * Every type in the compilation that declares {@code @PII} or {@code @NoMask}, and every type that
 * holds one — transitively, through fields, record components, arrays and generic type arguments,
 * and whether the annotated type is being compiled here or comes from a dependency. That last part
 * is what covers a wrapper such as {@code Portfolio}, which declares nothing itself but is the
 * object the engine actually walks, and what makes the standard multi-module layout work: domain
 * types in one module, the wrapper that holds them in another.
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

    /**
     * Types the JDK owns, which never carry {@code @PII} and whose members are not worth walking.
     *
     * <p>Without this the search for a type that holds something annotated would follow every field
     * of every JDK type a domain touches. It would still terminate and still give the same answer;
     * it would just spend the compilation doing it.
     */
    private static final List<String> PLATFORM_PACKAGES = List.of("java.", "javax.", "jdk.", "sun.", "com.sun.");

    /**
     * How many types from outside the compilation the search for annotated members may read before
     * it stops widening. A field of a framework type leads into that framework's whole object graph,
     * and reading several thousand class files to find no annotation is not a cost to put on every
     * build. What falls outside the budget loses nothing but its plan.
     */
    private static final int RESOLUTION_BUDGET = 500;

    /** Generated class names, accumulated across rounds, so a type is never written twice. */
    private final Set<String> generated = new TreeSet<>();

    private PlanAnalyzer analyzer;
    private Elements elements;
    private boolean verbose;

    /**
     * Every annotation, which is the only way to be asked about a compilation that has none.
     *
     * <p>Declaring {@code @PII} and {@code @NoMask} reads better and does not work: javac calls a
     * processor only when an annotation it asked for is present in the round, and the module that
     * needs this one most is precisely the one that annotates nothing — the module holding the
     * wrappers, whose annotated members all live in the domain module it depends on. Nothing is
     * claimed either way, so no other processor is affected by this.
     */
    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of("*");
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public synchronized void init(ProcessingEnvironment environment) {
        super.init(environment);
        this.analyzer = new PlanAnalyzer(environment.getTypeUtils(), environment.getElementUtils());
        this.elements = environment.getElementUtils();
        this.verbose = Boolean.parseBoolean(environment.getOptions().get(VERBOSE_OPTION));
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        try {
            if (round.processingOver()) {
                note("generated " + generated.size() + " mask plan" + (generated.size() == 1 ? "" : "s"));
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
     *
     * <p>The fixed point is taken over every type <em>reachable</em> from the compilation, not over
     * the compilation itself. A domain module and the module that wraps it is the standard layout,
     * and a wrapper whose annotated member arrives from a jar has exactly the same claim to a plan
     * as one whose member is compiled beside it. Only types being compiled here can be written for,
     * which is what the final filter is.
     */
    private List<TypeElement> typesWorthPlanning(Map<String, TypeElement> candidates) {
        Map<String, Set<String>> holds = whatEachTypeHolds(candidates);

        // The edges the other way round: which types hold this one. The answer is reached by walking
        // out from the annotated types rather than by iterating the whole map to a fixed point, so a
        // module with thousands of classes costs one pass over the edges instead of one per round.
        Map<String, Set<String>> heldBy = new LinkedHashMap<>();
        holds.forEach((holder, members) -> members.forEach(member ->
                heldBy.computeIfAbsent(member, key -> new LinkedHashSet<>()).add(holder)));

        Set<String> included = new LinkedHashSet<>();
        Deque<String> pending = new ArrayDeque<>();
        for (String name : holds.keySet()) {
            TypeElement type = resolve(candidates, name);
            if (type != null && declaresMasking(type) && included.add(name)) {
                pending.add(name);
            }
        }
        while (!pending.isEmpty()) {
            for (String holder : heldBy.getOrDefault(pending.poll(), Set.of())) {
                if (included.add(holder)) {
                    pending.add(holder);
                }
            }
        }

        return candidates.entrySet().stream()
                .filter(candidate -> included.contains(candidate.getKey()))
                .map(Map.Entry::getValue)
                .toList();
    }

    /**
     * What every type in reach holds: the ones being compiled, and then the ones their members name,
     * resolved through {@code Elements} so a type from a dependency answers exactly as a sibling
     * source file would.
     *
     * <p>That is what makes the standard multi-module layout work. {@code @PII} and {@code @NoMask}
     * are {@code RUNTIME}-retained, so they survive into a class file and the element model hands
     * them back; a wrapper whose annotated member arrives from a jar is then indistinguishable from
     * one whose member is compiled beside it, which is the point. It is also what keeps the
     * processor isolating: the decision for a type comes from the type and what it references, never
     * from what else happens to be in the round.
     *
     * <p>The walk is breadth-first and stops after {@link #RESOLUTION_BUDGET} types from outside the
     * compilation. A field of a framework type otherwise leads into that framework's entire object
     * graph, and reading several thousand class files to find no annotation is not a cost to put on
     * every build. Breadth-first is what makes the budget safe to spend: a domain type one or two
     * members away is always reached before anything deep, and what falls off the end loses nothing
     * but its plan — the reflective compiler covers it exactly as it did before.
     */
    private Map<String, Set<String>> whatEachTypeHolds(Map<String, TypeElement> candidates) {
        Map<String, Set<String>> holds = new LinkedHashMap<>();
        candidates.keySet().forEach(name -> holds.put(name, Set.of()));
        Deque<TypeElement> pending = new ArrayDeque<>(candidates.values());
        int resolved = 0;
        while (!pending.isEmpty()) {
            TypeElement current = pending.poll();
            Set<String> members = memberTypeNames(current);
            holds.put(current.getQualifiedName().toString(), members);
            for (String name : members) {
                if (holds.containsKey(name) || isPlatformType(name) || resolved >= RESOLUTION_BUDGET) {
                    continue;
                }
                TypeElement member = elements.getTypeElement(name);
                if (member != null) {
                    resolved++;
                    // Placed before it is walked, so a cycle is entered once and no path is followed
                    // twice.
                    holds.put(name, Set.of());
                    pending.add(member);
                }
            }
        }
        return holds;
    }

    /**
     * The element behind a name. Candidates are already in hand; anything else was resolved during
     * the walk and is asked for again rather than carried alongside, because {@code Elements} caches
     * it and a second map would have to be kept in step with the first.
     */
    private TypeElement resolve(Map<String, TypeElement> candidates, String name) {
        TypeElement candidate = candidates.get(name);
        return candidate != null ? candidate : elements.getTypeElement(name);
    }

    private static boolean isPlatformType(String qualifiedName) {
        return PLATFORM_PACKAGES.stream().anyMatch(qualifiedName::startsWith);
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

    /**
     * Every declared type an instance field or record component of this type could hold, type
     * arguments included. Static fields are left out because no plan ever reads one — the engine
     * masks an instance, and a static member is not part of one.
     */
    private Set<String> memberTypeNames(TypeElement type) {
        Set<String> names = new LinkedHashSet<>();
        List<TypeMirror> members = new ArrayList<>();
        ElementFilter.fieldsIn(type.getEnclosedElements()).stream()
                .filter(field -> !field.getModifiers().contains(Modifier.STATIC))
                .forEach(field -> members.add(field.asType()));
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
     * The name a plan is written under, and the only thing that connects it to its type.
     *
     * <p>There is deliberately no index. A single {@code META-INF/services} file listing every plan
     * is the obvious alternative and was what this processor wrote, but {@code Filer} cannot append
     * to a resource: the file has to be rewritten whole every time, and on an incremental build the
     * processor is handed only the sources that changed. It would rewrite the index with a fraction
     * of the plans in it and silently return every untouched type to reflection — worst of all for
     * a wrapper nobody edits and everybody masks. A name each type can be asked for instead has no
     * such shared state, which is what makes an incremental build safe and lets this processor
     * declare itself isolating to Gradle.
     */
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
