/**
 * Keeps PII out of the logs, which is where it escapes from.
 *
 * <p>{@link ch.raph.datamask.logback.MaskingAppender} wraps the appenders an application already has
 * and covers everything underneath it; {@link ch.raph.datamask.logback.LoggingEventMasker} does the
 * same job on a single event, for a custom appender or an encoder.
 *
 * <p>A log line is assembled from more places than a DTO is, and each of them leaks differently. An
 * argument carries a whole domain object, and is masked from its declarations. A concatenated message
 * carries a value with nothing declared about it anywhere. MDC is attached to every line by code
 * nobody re-reads. An exception message quotes the row that failed to insert. All four are handled
 * here, before the event reaches an appender — so a pattern layout, a JSON encoder and a log shipper
 * all see the same masked event, and appenders that were already configured are untouched.
 *
 * <p>The package is {@code @NullMarked}: every type in every signature here is non-null unless it
 * is annotated {@code @Nullable}.
 */
@NullMarked
package ch.raph.datamask.logback;

import org.jspecify.annotations.NullMarked;
