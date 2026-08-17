package ch.raph.datamask.benchmarks;

import ch.raph.datamask.application.DataMask;
import ch.raph.datamask.application.MaskPlanCompiler;
import ch.raph.datamask.application.MaskingEngine;
import ch.raph.datamask.domain.PolicyOverrides;
import ch.raph.datamask.infrastructure.generated.GeneratedMaskPlanCompiler;
import ch.raph.datamask.infrastructure.reflect.ReflectiveMaskPlanCompiler;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Reflection against generation — the difference {@code datamask-build-processor} exists to make.
 *
 * <p>Two questions, and they have different answers, which is why both are here.
 *
 * <p><b>What a type costs the first time it is seen.</b> {@link #compilePlansReflectively} derives
 * three plans by walking record components, resolving annotations and unreflecting a {@code
 * MethodHandle} per member. {@link #compilePlansFromGeneratedCode} asks the classloader for the
 * class the processor wrote beside each type and calls its {@code plan()}. A fresh compiler is built
 * inside each invocation on purpose: both implementations cache in a {@code ClassValue}, so
 * measuring a shared one would measure the cache. That makes these two numbers the startup cost of a
 * type, paid once per class per process — and it is why the generated arm carries one {@code
 * ServiceLoader} scan of an empty service registry that the reflective arm does not.
 *
 * <p><b>What a type costs afterwards.</b> {@link #maskWithReflectivePlans} and
 * {@link #maskWithGeneratedPlans} mask the same object through warm engines. A generated plan reads
 * members through a direct call and rebuilds through a direct constructor invocation, with no {@code
 * MethodHandle} and no {@code setAccessible} anywhere; the reflective plan invokes a {@code
 * MethodHandle} per member. This pair is the steady-state number, and it is the one that decides
 * whether the processor is worth putting on a build.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
@State(Scope.Benchmark)
public class PlanCompilerBenchmark {

    private ClassLoader classLoader;
    private MaskingEngine reflectiveEngine;
    private MaskingEngine generatedEngine;
    private BenchmarkDomain.Customer customer;

    @Setup
    public void setUp() {
        classLoader = PlanCompilerBenchmark.class.getClassLoader();
        customer = BenchmarkDomain.customer();

        reflectiveEngine = DataMask.builder()
                .secret(Fixtures.SECRET)
                .compiler(new ReflectiveMaskPlanCompiler())
                .build()
                .engine();
        generatedEngine = DataMask.builder()
                .secret(Fixtures.SECRET)
                .compiler(GeneratedMaskPlanCompiler.orReflective(PolicyOverrides.none(), classLoader))
                .build()
                .engine();

        Fixtures.requireSomethingMasked(customer, reflectiveEngine.mask(customer));
        Fixtures.requireSomethingMasked(customer, generatedEngine.mask(customer));
        requireGeneratedPlansWereFound();
    }

    /**
     * The comparison is only a comparison if the processor actually ran. Without the generated
     * classes on the classpath {@code GeneratedMaskPlanCompiler} falls back to the reflective one for
     * every type — silently, by design — and both arms would measure the same thing while looking
     * like a result.
     */
    private void requireGeneratedPlansWereFound() {
        MaskPlanCompiler compiler = generatedEngine.compiler();
        if (!(compiler instanceof GeneratedMaskPlanCompiler generated) || generated.generatedPlanCount() == 0) {
            throw new IllegalStateException("No generated mask plan was found for the benchmark domain, so the "
                    + "generated arm would be measuring reflection. Is datamask-build-processor still on this "
                    + "module's annotation processor path?");
        }
    }

    @Benchmark
    public void compilePlansReflectively(Blackhole blackhole) {
        MaskPlanCompiler compiler = new ReflectiveMaskPlanCompiler();
        blackhole.consume(compiler.planFor(BenchmarkDomain.Customer.class));
        blackhole.consume(compiler.planFor(BenchmarkDomain.Address.class));
        blackhole.consume(compiler.planFor(BenchmarkDomain.Card.class));
    }

    @Benchmark
    public void compilePlansFromGeneratedCode(Blackhole blackhole) {
        MaskPlanCompiler compiler = GeneratedMaskPlanCompiler.orReflective(PolicyOverrides.none(), classLoader);
        blackhole.consume(compiler.planFor(BenchmarkDomain.Customer.class));
        blackhole.consume(compiler.planFor(BenchmarkDomain.Address.class));
        blackhole.consume(compiler.planFor(BenchmarkDomain.Card.class));
    }

    @Benchmark
    public Object maskWithReflectivePlans() {
        return reflectiveEngine.mask(customer);
    }

    @Benchmark
    public Object maskWithGeneratedPlans() {
        return generatedEngine.mask(customer);
    }
}
