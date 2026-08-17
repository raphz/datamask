package ch.raph.datamask.infrastructure.generated;

import ch.raph.datamask.application.MaskPlanCompiler;
import ch.raph.datamask.domain.MaskPlan;
import ch.raph.datamask.domain.PolicyOverrides;
import ch.raph.datamask.infrastructure.reflect.ReflectiveMaskPlanCompiler;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Answers from plans generated at build time, and reflects over anything that has none.
 *
 * <h2>How a plan is found</h2>
 *
 * By name. {@code com.acme.Customer} is masked by {@code com.acme.Customer_MaskPlan}, which the
 * processor wrote into the same package while the type was compiled, and this asks the classloader
 * for it once per class. There is no index, on purpose: a {@code META-INF/services} file listing
 * every plan cannot be appended to, so an incremental build — which sees only the sources that
 * changed — would rewrite it with a fraction of the plans in it and silently return every untouched
 * type to reflection. A name has no such shared state. Plans registered through
 * {@link java.util.ServiceLoader} are still honoured, so a hand-written {@link GeneratedMaskPlan}
 * keeps working, and they take precedence over anything found by name.
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
 * <h2>Policy overrides turn it off, one type at a time</h2>
 *
 * A generated plan resolves {@code @PII} at compile time, when {@link PolicyOverrides} does not
 * exist yet. Answering from one while an override reaches it would silently ignore that override,
 * and an ignored override means a value the deployment asked to mask coming out unmasked — the one
 * class of bug this library must not have. So a plan an override touches is given up and its type
 * reflected over: slower, and correct.
 *
 * <p>Only that type, though. Any override used to discard every generated plan in the application,
 * so one line of configuration for one DTO silently turned build-time masking off everywhere, with
 * nothing in a log to say so. What counts as touched is deliberately generous — the type named by a
 * member override, a type named outright, and any type holding a member whose declared type an
 * override names — because being wrong in that direction only costs speed.
 */
public final class GeneratedMaskPlanCompiler implements MaskPlanCompiler {

    /** {@code com.acme.Customer} is planned by {@code com.acme.Customer_MaskPlan}. */
    private static final String PLAN_SUFFIX = "_MaskPlan";

    private final Map<Class<?>, MaskPlan> generated;
    private final MaskPlanCompiler fallback;

    /**
     * Where to look a plan up by name, or {@code null} when this compiler was handed its plans and
     * must answer from those alone — which is what the public constructor builds, and what a test or
     * an application assembling its own compiler gets.
     */
    private final ClassLoader classLoader;

    private final PolicyOverrides overrides;

    /** Names rather than classes, so a diagnostic counter cannot pin a classloader in memory. */
    private final Set<String> foundByName = ConcurrentHashMap.newKeySet();

    /**
     * One lookup per class, kept in a {@code ClassValue} for the same reason the reflective compiler
     * keeps its plans in one: a class and everything derived from it are collected together when
     * their classloader goes away.
     */
    private final ClassValue<Optional<MaskPlan>> byName = new ClassValue<>() {
        @Override
        protected Optional<MaskPlan> computeValue(Class<?> type) {
            return lookUp(type);
        }
    };

    public GeneratedMaskPlanCompiler(Map<Class<?>, MaskPlan> generated, MaskPlanCompiler fallback) {
        this(generated, fallback, null, PolicyOverrides.none());
    }

    private GeneratedMaskPlanCompiler(
            Map<Class<?>, MaskPlan> generated,
            MaskPlanCompiler fallback,
            ClassLoader classLoader,
            PolicyOverrides overrides) {
        this.generated = Map.copyOf(generated);
        this.fallback = Objects.requireNonNull(fallback, "fallback");
        this.classLoader = classLoader;
        this.overrides = overrides;
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
     * everywhere else.
     *
     * <p>Whether a plan exists is asked one class at a time, by name, so there is nothing to answer
     * up front and no way to hand back the plain reflective compiler for an application that has no
     * generated plans at all. What that application pays is one failed class lookup per type it
     * masks, once, remembered afterwards — next to deriving a plan by reflection, which is what
     * happens immediately after, it does not register.
     */
    public static MaskPlanCompiler orReflective(PolicyOverrides overrides, ClassLoader classLoader) {
        ReflectiveMaskPlanCompiler reflective = new ReflectiveMaskPlanCompiler(overrides);
        Map<Class<?>, MaskPlan> plans = load(classLoader);
        plans.entrySet().removeIf(entry -> isOverridden(entry.getKey(), entry.getValue(), overrides));
        return new GeneratedMaskPlanCompiler(plans, reflective, classLoader, overrides);
    }

    /**
     * Whether an override reaches this plan, in which case the plan has to be given up and the type
     * reflected over instead — a plan resolved at compile time cannot know about an override, and an
     * ignored override means a value the deployment asked to mask coming out unmasked.
     *
     * <p>What changed is the blast radius. Any override used to discard every generated plan in the
     * application: one line of configuration for one DTO silently turned build-time masking off
     * everywhere, with nothing to notice it by. Now only the plans an override can actually affect
     * are dropped — the type named by a member override, and any type holding a member whose
     * declared type a type-level override names.
     */
    private static boolean isOverridden(Class<?> type, MaskPlan plan, PolicyOverrides overrides) {
        if (overrides.isEmpty()) {
            return false;
        }
        String prefix = type.getName() + "#";
        if (overrides.byMember().keySet().stream().anyMatch(key -> key.startsWith(prefix))) {
            return true;
        }
        Set<String> overriddenTypes = overrides.byType().keySet();
        if (overriddenTypes.contains(type.getName())) {
            return true;
        }
        return plan.members().stream()
                .anyMatch(
                        member -> overriddenTypes.contains(member.declaredType().getName()));
    }

    @Override
    public MaskPlan planFor(Class<?> type) {
        MaskPlan plan = generated.get(type);
        if (plan != null) {
            return plan;
        }
        return byName.get(type).orElseGet(() -> fallback.planFor(type));
    }

    /**
     * How many types have been answered for from a plan worked out at build time: the ones handed to
     * this compiler, plus the ones it has since found by name. For diagnostics and for tests.
     *
     * <p>It grows as types are masked, because a plan is only looked for when its type first turns
     * up. Zero after a mask or two is the signal that the processor is not on the annotation path.
     */
    public int generatedPlanCount() {
        return generated.size() + foundByName.size();
    }

    /**
     * The plan compiled beside this type, if the processor wrote one and no override reaches it.
     *
     * <p>Everything that can go wrong here ends the same way — {@link Optional#empty()}, and the type
     * is reflected over instead. A missing class is the ordinary case. A class that is there but
     * broken, or one that answers for a different type than its name claims, is a stale build or a
     * name collision, and neither is worth failing an application's startup over when a slower route
     * to the same plan is one call away.
     */
    private Optional<MaskPlan> lookUp(Class<?> type) {
        if (classLoader == null
                || type.isArray()
                || type.isPrimitive()
                || type.getSimpleName().isEmpty()) {
            return Optional.empty();
        }
        String name = planNameOf(type);
        for (ClassLoader candidate : loadersFor(type)) {
            try {
                Class<?> planClass = Class.forName(name, true, candidate);
                if (!GeneratedMaskPlan.class.isAssignableFrom(planClass)) {
                    return Optional.empty();
                }
                GeneratedMaskPlan found =
                        (GeneratedMaskPlan) planClass.getDeclaredConstructor().newInstance();
                if (found.type() != type) {
                    return Optional.empty();
                }
                MaskPlan plan = found.plan();
                // The same rule the registered plans go through, applied one type at a time: a plan
                // an override reaches is given up, because a plan resolved at compile time cannot
                // know about one and an ignored override is an unmasked value.
                if (isOverridden(type, plan, overrides)) {
                    return Optional.empty();
                }
                foundByName.add(type.getName());
                return Optional.of(plan);
            } catch (ClassNotFoundException nothingGenerated) {
                // Try the next classloader, then reflection.
            } catch (ReflectiveOperationException | RuntimeException | LinkageError broken) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /**
     * Where the plan for this type could be. Its own classloader first, because that is where the
     * processor compiled it to; then the one this compiler was built with, which is what an
     * application that assembles its own classpath — a fat jar, a container — actually reads.
     */
    private List<ClassLoader> loadersFor(Class<?> type) {
        ClassLoader own = type.getClassLoader();
        return own == null || own == classLoader ? List.of(classLoader) : List.of(own, classLoader);
    }

    /**
     * {@code com.acme.Banking.Customer} becomes {@code com.acme.Banking_Customer_MaskPlan}, which is
     * what {@code PlanAnalyzer.generatedNameOf} writes. Built from the enclosing classes rather than
     * by replacing {@code $} in the binary name, because {@code $} is a legal character in a class
     * name of its own and the two are not distinguishable afterwards.
     */
    private static String planNameOf(Class<?> type) {
        StringBuilder name = new StringBuilder(type.getSimpleName());
        for (Class<?> enclosing = type.getEnclosingClass();
                enclosing != null;
                enclosing = enclosing.getEnclosingClass()) {
            name.insert(0, '_').insert(0, enclosing.getSimpleName());
        }
        name.append(PLAN_SUFFIX);
        String packageName = type.getPackageName();
        return packageName.isEmpty() ? name.toString() : packageName + "." + name;
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
        // Iterated as a stream of providers rather than with a for-each, because a corrupt or stale
        // service registration makes the *iterator itself* throw ServiceConfigurationError — before
        // any provider is handed over, and from outside the loop body, where the guard below could
        // never see it. That one escaping error takes the application's startup with it, which is
        // exactly what a build-time optimisation must never be able to do.
        Iterator<ServiceLoader.Provider<GeneratedMaskPlan>> providers =
                ServiceLoader.load(GeneratedMaskPlan.class, classLoader).stream()
                        .iterator();
        while (true) {
            ServiceLoader.Provider<GeneratedMaskPlan> provider;
            try {
                if (!providers.hasNext()) {
                    return plans;
                }
                provider = providers.next();
            } catch (ServiceConfigurationError | RuntimeException | LinkageError unreadable) {
                // The service file itself is malformed or names a class that is gone. Nothing more
                // can be read from it, so every remaining type falls back to reflection.
                return plans;
            }
            try {
                GeneratedMaskPlan generated = provider.get();
                plans.put(generated.type(), generated.plan());
            } catch (ServiceConfigurationError | RuntimeException | LinkageError broken) {
                // Only this type falls back to reflection, which produces the same plan by a
                // slower route. A generated plan is an optimisation, and an optimisation that
                // refuses to start is worse than the thing it replaced.
            }
        }
    }
}
