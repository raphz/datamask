/**
 * The use cases: the facade an application holds, the engine that walks an object graph, and the
 * sanitiser that masks PII inside free-form text.
 *
 * <p>{@link ch.raph.datamask.application.DataMask} is what an application configures and calls;
 * {@link ch.raph.datamask.application.MaskingEngine} is what an integration module holds.
 * {@code DataMask.Builder} and {@code MaskerRegistry} are the composition root, which is why this
 * package is the one allowed to reach into {@code infrastructure}.
 *
 * <p>The package is {@code @NullMarked}: every type in every signature here is non-null unless it
 * is annotated {@code @Nullable}. Masking accepts and produces {@code null} throughout — a
 * {@code null} in gives a {@code null} back, and a member that could not be masked safely becomes
 * {@code null} rather than staying raw — so the entry points are annotated on both sides.
 */
@NullMarked
package ch.raph.datamask.application;

import org.jspecify.annotations.NullMarked;
