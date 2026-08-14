/**
 * Keeps row values out of what the database tells the application.
 *
 * <p>{@link ch.raph.datamask.jdbc.MaskingDataSource} wraps a pool and covers everything underneath
 * it; {@link ch.raph.datamask.jdbc.SqlExceptionSanitizer} does the same job on a single exception,
 * for code that catches one somewhere else.
 *
 * <p>The leak worth the module is the error detail. A constraint violation is answered with the row
 * that caused it — {@code Detail: Key (email)=(john@x.com) already exists.} — and that text is the
 * exception's message, so it travels into the stack trace, the log and the error tracker. Unlike a
 * field on a DTO there is no annotation to put on it and no obvious place to look, which is why it
 * survives in systems that mask everything else.
 */
package ch.raph.datamask.jdbc;
