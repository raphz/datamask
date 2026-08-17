package ch.raph.datamask.benchmarks;

import ch.raph.datamask.application.DataMask;
import ch.raph.datamask.application.MaskingEngine;
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

/**
 * What {@code MaskingEngine.mask} costs on a graph with nothing to mask, and on the same graph with
 * PII in it.
 *
 * <p>The first is the number that matters most, because it is the one an application pays on almost
 * every call. The engine returns the <em>same instance</em> when no member changed, so a PII-free
 * graph allocates no copy — but it still walks every member, and under a strict policy it still
 * scans every string it finds. {@link #maskCleanGraphWithoutTextScan} is that second half taken
 * away: the gap between it and {@link #maskCleanGraph} is what content scanning costs a graph that
 * had nothing to find, which is the cost the improvements document proposes to remove with a
 * character-class pre-filter.
 *
 * <p>Plans are compiled on first use and cached in a {@code ClassValue}, so warmup takes plan
 * compilation out of the measurement here. {@link PlanCompilerBenchmark} is where that cost is
 * measured on purpose.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
@State(Scope.Benchmark)
public class MaskingEngineBenchmark {

    private MaskingEngine engine;
    private MaskingEngine engineWithoutTextScan;
    private BenchmarkDomain.Customer customer;
    private BenchmarkDomain.Shipment shipment;

    @Setup
    public void setUp() {
        DataMask dataMask = Fixtures.dataMask();
        DataMask withoutTextScan = DataMask.builder()
                .secret(Fixtures.SECRET)
                .policy(dataMask.policy().withScanUnannotatedText(false))
                .build();

        engine = dataMask.engine();
        engineWithoutTextScan = withoutTextScan.engine();
        customer = BenchmarkDomain.customer();
        shipment = BenchmarkDomain.shipment();

        // Both directions checked, because either one failing silently would leave a plausible
        // number that measured the wrong path.
        Fixtures.requireSomethingMasked(customer, engine.mask(customer));
        if (engine.mask(shipment) != shipment) {
            throw new IllegalStateException("The PII-free graph was rebuilt, so this is not the short-circuit path.");
        }
    }

    /** The baseline: a graph the engine finds nothing in, returned as the same instance. */
    @Benchmark
    public Object maskCleanGraph() {
        return engine.mask(shipment);
    }

    /** The same graph with the detectors switched off, which leaves the walk and nothing else. */
    @Benchmark
    public Object maskCleanGraphWithoutTextScan() {
        return engineWithoutTextScan.mask(shipment);
    }

    /** The same shape, six declared members deep, all of which have to be masked and rebuilt. */
    @Benchmark
    public Object maskGraphWithPii() {
        return engine.mask(customer);
    }
}
