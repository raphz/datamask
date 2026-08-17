package ch.raph.datamask.benchmarks;

import ch.raph.datamask.application.DataMask;
import ch.raph.datamask.log4j2.MaskingRewritePolicy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.rewrite.RewritePolicy;
import org.apache.logging.log4j.core.impl.ContextDataFactory;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.SimpleMessage;
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
 * The same question as {@link LogbackAppenderBenchmark}, asked of log4j2.
 *
 * <p>The unit here is the {@link RewritePolicy}, because that is where {@code datamask-log4j2} sits:
 * a {@code Rewrite} appender calls it once per event and forwards whatever comes back. The baseline
 * is therefore a policy that returns its argument — a floor of roughly nothing, which is the honest
 * comparison when the alternative is a rewrite that does no work at all. What the pair reports is the
 * whole cost of the masking policy, not a difference between two appenders.
 *
 * <p>The thread context map is the log4j2 equivalent of MDC, and the rich event carries three entries
 * of it plus an exception with a cause, for the same reason the logback benchmark does: those are
 * per-event costs an application pays on every line, not only on the lines that turn out to carry
 * something.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
@State(Scope.Benchmark)
public class Log4j2RewriteBenchmark {

    private static final String LOGGER_NAME = "ch.raph.datamask.benchmarks.PaymentService";

    /** The floor: a rewrite policy that does nothing, which is what a Rewrite appender costs empty. */
    private static final RewritePolicy IDENTITY = source -> source;

    private RewritePolicy masking;

    private LogEvent cleanEvent;
    private LogEvent ibanEvent;
    private LogEvent panEvent;
    private LogEvent richEvent;

    @Setup
    public void setUp() {
        DataMask dataMask = Fixtures.dataMask();
        Fixtures.requireNothingDetected(dataMask, Fixtures.CLEAN_MESSAGE);

        masking = new MaskingRewritePolicy(dataMask);

        cleanEvent = event(Fixtures.CLEAN_MESSAGE, null, Map.of());
        ibanEvent = event(Fixtures.IBAN_MESSAGE, null, Map.of());
        panEvent = event(Fixtures.PAN_MESSAGE, null, Map.of());
        richEvent = event(Fixtures.CLEAN_MESSAGE, constraintViolation(), contextData());

        if (masking.rewrite(cleanEvent) != cleanEvent) {
            throw new IllegalStateException("The clean event was rebuilt, so this is not the short-circuit path.");
        }
        Fixtures.requireSomethingMasked(ibanEvent, masking.rewrite(ibanEvent));
        Fixtures.requireSomethingMasked(panEvent, masking.rewrite(panEvent));
        Fixtures.requireSomethingMasked(richEvent, masking.rewrite(richEvent));
    }

    /** The baseline: what the rewrite call itself costs when the policy does nothing. */
    @Benchmark
    public LogEvent identityRewriteCleanLine() {
        return IDENTITY.rewrite(cleanEvent);
    }

    /** The same event through the masking policy, and the number this module exists to report. */
    @Benchmark
    public LogEvent maskingRewriteCleanLine() {
        return masking.rewrite(cleanEvent);
    }

    @Benchmark
    public LogEvent maskingRewriteIbanLine() {
        return masking.rewrite(ibanEvent);
    }

    @Benchmark
    public LogEvent maskingRewriteCardLine() {
        return masking.rewrite(panEvent);
    }

    /** The baseline for the event below: three context entries and an exception with a cause. */
    @Benchmark
    public LogEvent identityRewriteContextAndException() {
        return IDENTITY.rewrite(richEvent);
    }

    @Benchmark
    public LogEvent maskingRewriteContextAndException() {
        return masking.rewrite(richEvent);
    }

    private static LogEvent event(String message, Throwable thrown, Map<String, String> contextData) {
        return new Log4jLogEvent.Builder()
                .setLoggerName(LOGGER_NAME)
                .setLevel(Level.INFO)
                .setMessage(new SimpleMessage(message))
                .setThrown(thrown)
                .setContextData(ContextDataFactory.createContextData(contextData))
                .setThreadName("benchmark")
                .build();
    }

    /** The ordinary leak: a constraint violation answering with the row that caused it. */
    private static Throwable constraintViolation() {
        Throwable cause = new IllegalStateException("duplicate key value violates unique constraint"
                + " \"customer_iban_key\": Key (iban)=(CH9300762011623852957) already exists");
        return new RuntimeException("could not persist customer", cause);
    }

    /** A request-scoped context map of the shape a service actually sets, one entry carrying PII. */
    private static Map<String, String> contextData() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("requestId", "batch settlement");
        values.put("tenant", "retail switzerland");
        values.put("customer", "jean.dupont@example.ch");
        return Map.copyOf(values);
    }
}
