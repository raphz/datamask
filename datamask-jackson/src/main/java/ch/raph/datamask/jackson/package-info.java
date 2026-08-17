/**
 * Masks PII while JSON is being written.
 *
 * <p>{@link ch.raph.datamask.jackson.DataMaskModule} is the only type an application touches;
 * everything else here is the plumbing behind it.
 *
 * <p>Serialization is the narrowest point every outbound value passes through, which makes it the
 * cheapest place to enforce masking: no masked copy of the object graph is built, no call site
 * changes, and a field added to a DTO next year is covered the day it is added.
 *
 * <p>The package is {@code @NullMarked}: every type in every signature here is non-null unless it
 * is annotated {@code @Nullable}.
 */
@NullMarked
package ch.raph.datamask.jackson;

import org.jspecify.annotations.NullMarked;
