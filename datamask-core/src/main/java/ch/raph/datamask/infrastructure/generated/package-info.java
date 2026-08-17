/**
 * The runtime half of build-time plan generation: the SPI generated code implements, and the
 * compiler that reads it back.
 *
 * <p>{@code GeneratedMaskPlanCompiler} falls back to the reflective compiler for anything the
 * processor did not cover, which is what keeps generation optional rather than a second engine, and
 * steps aside entirely when {@code PolicyOverrides} is non-empty — a plan resolved at compile time
 * cannot know about an override, and ignoring one would mean an unmasked value.
 *
 * <p>The package is {@code @NullMarked}: every type in every signature here is non-null unless it
 * is annotated {@code @Nullable}.
 */
@NullMarked
package ch.raph.datamask.infrastructure.generated;

import org.jspecify.annotations.NullMarked;
