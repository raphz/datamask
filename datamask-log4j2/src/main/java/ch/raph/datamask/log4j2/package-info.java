/**
 * Keeps PII out of the logs, which is where it escapes from.
 *
 * <p>{@link ch.raph.datamask.log4j2.MaskingRewritePolicy} masks the event itself, inside the
 * {@code Rewrite} appender log4j2 already provides, and covers everything downstream of it;
 * {@link ch.raph.datamask.log4j2.MaskingMessagePatternConverter} masks the message from inside a layout,
 * for a configuration that cannot be restructured. {@link ch.raph.datamask.log4j2.LogEventMasker} does
 * the work for both, and is public for a custom appender or filter.
 *
 * <p>A log line is assembled from more places than a DTO is, and each of them leaks differently. A
 * parameter carries a whole domain object, and is masked from its declarations. A concatenated message
 * carries a value with nothing declared about it anywhere. The thread context map is attached to every
 * line by code nobody re-reads. An exception message quotes the row that failed to insert. All four are
 * handled before the event reaches an appender.
 *
 * <p>The plugins are registered through the descriptor log4j2's own annotation processor writes at build
 * time, so nothing has to scan a package for them.
 */
package ch.raph.datamask.log4j2;
