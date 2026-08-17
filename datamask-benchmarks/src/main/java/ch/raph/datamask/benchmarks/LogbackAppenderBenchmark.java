package ch.raph.datamask.benchmarks;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import ch.raph.datamask.application.DataMask;
import ch.raph.datamask.logback.LoggingEventMasker;
import ch.raph.datamask.logback.MaskingAppender;
import java.util.LinkedHashMap;
import java.util.Map;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * The headline: a clean {@code INFO} line through the masking appender, against the same event
 * through the appender underneath it.
 *
 * <p>{@link #plainAppenderCleanLine} appends the event straight to a sink that does nothing but keep
 * a reference to it. {@link #maskingAppenderCleanLine} appends the same event to a
 * {@link MaskingAppender} with that same sink attached, so the difference between the two is exactly
 * what inserting masking in front of an existing appender costs — the masker's scan of the message,
 * the MDC, the arguments and the exception chain, plus one extra {@code doAppend} frame. Nothing
 * about the sink changes, which is the point: this is the number an application would see by
 * wrapping the appenders it already has.
 *
 * <p>The events are built once and reused, so what is measured is the appender path rather than
 * logback's event construction, and so the clean event's {@code formattedMessage} is already cached
 * by the time the first measured invocation runs. That understates the masking arm slightly on the
 * message-formatting side and is called out in the module README rather than hidden.
 *
 * <p>The plain arm's cost does not depend on what the event carries — the sink stores a reference —
 * so {@link #plainAppenderCleanLine} is the baseline for the IBAN and card variants too. The rich
 * event, which carries three MDC entries and an exception with a cause, gets its own baseline
 * because it is a different event, not a different appender.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
@State(Scope.Benchmark)
public class LogbackAppenderBenchmark {

    private static final String LOGGER_NAME = "ch.raph.datamask.benchmarks.PaymentService";

    private LoggerContext context;
    private CapturingAppender sink;
    private MaskingAppender masking;

    private ILoggingEvent cleanEvent;
    private ILoggingEvent ibanEvent;
    private ILoggingEvent panEvent;
    private ILoggingEvent richEvent;

    @Setup
    public void setUp() {
        DataMask dataMask = Fixtures.dataMask();
        Fixtures.requireNothingDetected(dataMask, Fixtures.CLEAN_MESSAGE);

        context = new LoggerContext();
        context.setName("datamask-benchmarks");
        context.start();
        Logger logger = context.getLogger(LOGGER_NAME);

        sink = new CapturingAppender();
        sink.setName("SINK");
        sink.setContext(context);
        sink.start();

        masking = new MaskingAppender();
        masking.setName("MASKED");
        masking.setContext(context);
        masking.setDataMask(dataMask);
        masking.addAppender(sink);
        masking.start();

        cleanEvent = event(logger, Fixtures.CLEAN_MESSAGE, null, Map.of());
        ibanEvent = event(logger, Fixtures.IBAN_MESSAGE, null, Map.of());
        panEvent = event(logger, Fixtures.PAN_MESSAGE, null, Map.of());
        richEvent = event(logger, Fixtures.CLEAN_MESSAGE, constraintViolation(), mdc());

        verifyTheAppenderIsDoingWhatTheNumbersClaim(dataMask);
    }

    /**
     * A clean event has to come back as the same instance — that short-circuit is the whole reason a
     * clean line is cheap — and a dirty one has to come back changed. Either failing would leave a
     * number that looks like a result and measures the other path.
     */
    private void verifyTheAppenderIsDoingWhatTheNumbersClaim(DataMask dataMask) {
        LoggingEventMasker masker = new LoggingEventMasker(dataMask);
        if (masker.mask(cleanEvent) != cleanEvent) {
            throw new IllegalStateException("The clean event was rebuilt, so this is not the short-circuit path.");
        }
        Fixtures.requireSomethingMasked(ibanEvent, masker.mask(ibanEvent));
        Fixtures.requireSomethingMasked(panEvent, masker.mask(panEvent));
        Fixtures.requireSomethingMasked(richEvent, masker.mask(richEvent));

        masking.doAppend(cleanEvent);
        if (sink.last() != cleanEvent) {
            throw new IllegalStateException("The masking appender did not forward the clean event unchanged.");
        }
    }

    @TearDown
    public void tearDown() {
        masking.stop();
        context.stop();
    }

    /** The baseline: the sink on its own, with no masking in front of it. */
    @Benchmark
    public ILoggingEvent plainAppenderCleanLine() {
        sink.doAppend(cleanEvent);
        return sink.last();
    }

    /** The headline: the same event, the same sink, with the masking appender in front. */
    @Benchmark
    public ILoggingEvent maskingAppenderCleanLine() {
        masking.doAppend(cleanEvent);
        return sink.last();
    }

    /** The same line with an IBAN in the message: detection, masking and a rebuilt event. */
    @Benchmark
    public ILoggingEvent maskingAppenderIbanLine() {
        masking.doAppend(ibanEvent);
        return sink.last();
    }

    /** And with a card number, which is masked under PCI-DSS rules rather than partially revealed. */
    @Benchmark
    public ILoggingEvent maskingAppenderCardLine() {
        masking.doAppend(panEvent);
        return sink.last();
    }

    /** The baseline for the event below: three MDC entries and an exception with a cause. */
    @Benchmark
    public ILoggingEvent plainAppenderMdcAndException() {
        sink.doAppend(richEvent);
        return sink.last();
    }

    /**
     * The per-event costs the improvements document names: every MDC value is scanned, and every
     * message down the cause chain is scanned and the exception rebuilt around the masked text.
     */
    @Benchmark
    public ILoggingEvent maskingAppenderMdcAndException() {
        masking.doAppend(richEvent);
        return sink.last();
    }

    private static ILoggingEvent event(Logger logger, String message, Throwable thrown, Map<String, String> mdc) {
        LoggingEvent event = new LoggingEvent(Logger.FQCN, logger, Level.INFO, message, thrown, null);
        event.setThreadName("benchmark");
        // Set explicitly, including the empty case, so no measured invocation ever reaches into a
        // thread-local MDC — what an event carries has to be a property of the event here.
        event.setMDCPropertyMap(mdc);
        return event;
    }

    /** The ordinary leak: a constraint violation answering with the row that caused it. */
    private static Throwable constraintViolation() {
        Throwable cause = new IllegalStateException("duplicate key value violates unique constraint"
                + " \"customer_iban_key\": Key (iban)=(CH9300762011623852957) already exists");
        return new RuntimeException("could not persist customer", cause);
    }

    /** A request-scoped MDC of the shape a service actually sets, one entry of which carries PII. */
    private static Map<String, String> mdc() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("requestId", "batch settlement");
        values.put("tenant", "retail switzerland");
        values.put("customer", "jean.dupont@example.ch");
        return Map.copyOf(values);
    }

    /**
     * The sink: keeps a reference to the last event and does nothing else. Every appender an
     * application would put here — a console, a file, an encoder — costs more than this, so the ratio
     * this benchmark reports is the worst case for masking rather than a flattering one.
     */
    public static final class CapturingAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

        private ILoggingEvent last;

        @Override
        protected void append(ILoggingEvent event) {
            last = event;
        }

        ILoggingEvent last() {
            return last;
        }
    }
}
