package ch.raph.datamask.benchmarks;

import ch.raph.datamask.application.DataMask;
import ch.raph.datamask.application.TextSanitizer;
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
 * The regex fan-out, measured on text that matches nothing.
 *
 * <p>This is the cost the improvements document calls out as unfiltered: every string on every event
 * — the message, each MDC value, each exception message down the chain — is put through the whole
 * default detector set, and the overwhelmingly common answer is "no". {@link #sanitizeNoMatch} is
 * what that answer costs on a normal log line and {@link #sanitizeLongNoMatch} is the same answer on
 * a line ten times the length, which is what says whether the cost is per-call or per-character.
 *
 * <p>{@link #sanitizeWithIban} is the other side of it: what a line that does contain something
 * costs, including the pseudonymisation and the rebuild. A pre-filter would move the first two and
 * leave the third where it is, so the three numbers together are what says whether one is worth
 * writing.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
@State(Scope.Benchmark)
public class TextSanitizerBenchmark {

    private static final String PATH = "benchmark.message";

    private TextSanitizer sanitizer;
    private TextSanitizer uncapped;

    @Setup
    public void setUp() {
        DataMask dataMask = Fixtures.dataMask();
        sanitizer = dataMask.engine().sanitizer();

        DataMask withoutCap = Fixtures.uncappedDataMask();
        uncapped = withoutCap.engine().sanitizer();

        Fixtures.requireNothingDetected(dataMask, Fixtures.CLEAN_MESSAGE);
        Fixtures.requireNothingDetected(dataMask, Fixtures.NOISY_CLEAN_MESSAGE);
        Fixtures.requireNothingDetected(dataMask, Fixtures.LONG_CLEAN_MESSAGE);
        // Against the uncapped engine: the capped one truncates this fixture by design, which is
        // not the same thing as having found something in it.
        Fixtures.requireNothingDetected(withoutCap, Fixtures.OVERSIZED_CLEAN_MESSAGE);
        Fixtures.requireSomethingMasked(Fixtures.IBAN_MESSAGE, dataMask.maskText(Fixtures.IBAN_MESSAGE));
    }

    /** A normal log line, ~70 characters, with nothing in it any detector recognises. */
    @Benchmark
    public String sanitizeNoMatch() {
        return sanitizer.sanitize(Fixtures.CLEAN_MESSAGE, PATH);
    }

    /** The same answer on ~2 KB of text: the scan is linear, so this is where that shows. */
    @Benchmark
    public String sanitizeLongNoMatch() {
        return sanitizer.sanitize(Fixtures.LONG_CLEAN_MESSAGE, PATH);
    }

    /** One IBAN in the same short line: detection, then masking, then a rebuilt string. */
    @Benchmark
    public String sanitizeWithIban() {
        return sanitizer.sanitize(Fixtures.IBAN_MESSAGE, PATH);
    }

    /**
     * A clean line with an order number, a timestamp and a node id in it, which opens a third of the
     * detector gates. This is what the pre-filter is worth on text that has structure — the number
     * to quote, rather than the one measured on prose.
     */
    @Benchmark
    public String sanitizeNoMatchWithDigits() {
        return sanitizer.sanitize(Fixtures.NOISY_CLEAN_MESSAGE, PATH);
    }

    /** Sixty-four kilobytes, scanned as far as the policy's cap allows and redacted past it. */
    @Benchmark
    public String sanitizeOversizedCapped() {
        return sanitizer.sanitize(Fixtures.OVERSIZED_CLEAN_MESSAGE, PATH);
    }

    /**
     * The same text with the cap removed: what one oversized value used to cost the thread that
     * logged it, every time, with nothing bounding it.
     */
    @Benchmark
    public String sanitizeOversizedUncapped() {
        return uncapped.sanitize(Fixtures.OVERSIZED_CLEAN_MESSAGE, PATH);
    }
}
