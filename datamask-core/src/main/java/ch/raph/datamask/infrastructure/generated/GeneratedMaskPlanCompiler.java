package ch.raph.datamask.infrastructure.generated;

import ch.raph.datamask.application.MaskPlanCompiler;
import ch.raph.datamask.domain.MaskPlan;
import ch.raph.datamask.domain.PolicyOverrides;
import ch.raph.datamask.infrastructure.reflect.ReflectiveMaskPlanCompiler;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;

/**
 * Answers from plans generated at build time, and reflects over anything that has none.
 *
 * <p>The fallback is not a convenience, it is the design. An application masks types it did not
 * compile — a DTO generated from an OpenAPI contract, a third-party model, a class the processor was
 * never pointed at — and a compiler that could only answer for what it had seen would have to fail
 * on those. So every lookup that misses goes to {@link ReflectiveMaskPlanCompiler}, which means a
 * codebase can put the processor on its annotation path and gain generated plans for whatever it
 * happens to cover, one module at a time, with nothing else changing.
 *
 * <h2>What the generated path avoids</h2>
 *
 * A generated plan reads members through a direct call and rebuilds through a direct constructor
 * invocation. No {@code MethodHandle}, no {@code setAccessible}, no {@code privateLookupIn} — which
 * is what makes it work inside a module that opens nothing, and inside a GraalVM native image with
 * no reachability metadata for the masked types.
 *
 * <h2>Policy overrides turn it off</h2>
 *
 * A generated plan resolves {@code @PII} at compile time, when {@link PolicyOverrides} does not
 * exist yet. Answering from one while overrides are configured would silently ignore them, and an
 * ignored override means a value the deployment asked to mask coming out unmasked — the one class of
 * bug this library must not have. So {@link #orReflective} hands back the reflective compiler
 * whenever overrides are present: slower, and correct.
 */
public final class GeneratedMaskPlanCompiler implements MaskPlanCompiler {

    private final Map<Class<?>, MaskPlan> generated;
    private final MaskPlanCompiler fallback;

    public GeneratedMaskPlanCompiler(Map<Class<?>, MaskPlan> generated, MaskPlanCompiler fallback) {
        this.generated = Map.copyOf(generated);
        this.fallback = Objects.requireNonNull(fallback, "fallback");
    }

    /**
     * The compiler an application gets by default, looking for generated plans wherever the calling
     * thread would look for classes — the context classloader in a container, this library's own
     * when there is none.
     */
    public static MaskPlanCompiler orReflective(PolicyOverrides overrides) {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        return orReflective(overrides, context != null ? context : GeneratedMaskPlanCompiler.class.getClassLoader());
    }

    /**
     * The compiler an application gets by default: generated plans where they exist, reflection
     * everywhere else — unless overrides are configured, in which case reflection everywhere.
     *
     * <p>Returns the reflective compiler unchanged when nothing was generated, so an application
     * without the processor pays nothing for this indirection.
     */
    public static MaskPlanCompiler orReflective(PolicyOverrides overrides, ClassLoader classLoader) {
        ReflectiveMaskPlanCompiler reflective = new ReflectiveMaskPlanCompiler(overrides);
        if (!overrides.isEmpty()) {
            return reflective;
        }
        Map<Class<?>, MaskPlan> plans = load(classLoader);
        return plans.isEmpty() ? reflective : new GeneratedMaskPlanCompiler(plans, reflective);
    }

    @Override
    public MaskPlan planFor(Class<?> type) {
        MaskPlan plan = generated.get(type);
        return plan != null ? plan : fallback.planFor(type);
    }

    /** How many types were answered for at build time. For diagnostics and for tests. */
    public int generatedPlanCount() {
        return generated.size();
    }

    /**
     * Builds every plan once, here, rather than on the first request for each type — which is half
     * the point of generating them.
     *
     * <p>A {@code GeneratedMaskPlan} that fails to build is not allowed to take the application with
     * it: the type simply falls back to reflection, which produces the same plan by a slower route.
     * A generated plan is an optimisation, and an optimisation that refuses to start is worse than
     * the thing it replaced.
     */
    private static Map<Class<?>, MaskPlan> load(ClassLoader classLoader) {
        Map<Class<?>, MaskPlan> plans = new LinkedHashMap<>();
        for (GeneratedMaskPlan generated : ServiceLoader.load(GeneratedMaskPlan.class, classLoader)) {
            try {
                plans.put(generated.type(), generated.plan());
            } catch (RuntimeException | LinkageError ignored) {
                // Falls back to reflection for this type.
            }
        }
        return plans;
    }
}
