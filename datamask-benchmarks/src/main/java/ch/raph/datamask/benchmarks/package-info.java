/**
 * What masking costs, measured rather than asserted.
 *
 * <p>The headline is a clean {@code INFO} line — one carrying no PII at all — through
 * {@code datamask-logback}'s masking appender, against the same event through the appender
 * underneath it. That ratio is what decides whether the logging integrations can be put in front of
 * a production log volume, and it is the number this module exists to produce.
 *
 * <p>Everything else here isolates one decision:
 *
 * <ul>
 *   <li>{@link ch.raph.datamask.benchmarks.LogbackAppenderBenchmark} and
 *       {@link ch.raph.datamask.benchmarks.Log4j2RewriteBenchmark} — an event, clean and carrying
 *       PII, with and without masking in front of it.
 *   <li>{@link ch.raph.datamask.benchmarks.MaskingEngineBenchmark} — the no-change short-circuit on a
 *       PII-free object graph, against the same graph shape with PII in it.
 *   <li>{@link ch.raph.datamask.benchmarks.TextSanitizerBenchmark} — the regex fan-out on text that
 *       matches nothing, which is what every clean log line pays.
 *   <li>{@link ch.raph.datamask.benchmarks.PlanCompilerBenchmark} — plans derived by reflection
 *       against plans written at build time by {@code datamask-build-processor}.
 * </ul>
 *
 * <p>This module is never published. It applies {@code datamask.java-base-conventions} precisely so
 * it has no route to Maven Central, and it is excluded from the coverage aggregate for the same
 * reason {@code datamask-architecture-tests} is: it measures the library, it is not part of it.
 *
 * <p>The package is {@code @NullMarked}: every type in every signature here is non-null unless it
 * is annotated {@code @Nullable}.
 */
@NullMarked
package ch.raph.datamask.benchmarks;

import org.jspecify.annotations.NullMarked;
