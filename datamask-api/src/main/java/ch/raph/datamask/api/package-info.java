/**
 * Annotations and SPI contracts for DataMask.
 *
 * <p>This module has no dependencies at all, by design. A domain module can depend on it to
 * declare {@link ch.raph.datamask.api.PII} on its records without taking on the masking engine,
 * a reflection library, or a logging framework.
 *
 * <p>The package is {@code @NullMarked}: every type in every signature here is non-null unless it
 * is annotated {@code @Nullable}. The JSpecify annotations that say so are compile-only, so the
 * zero-dependency guarantee above still holds — nothing reads them at runtime.
 */
@NullMarked
package ch.raph.datamask.api;

import org.jspecify.annotations.NullMarked;
