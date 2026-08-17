/**
 * The reflective {@code MaskPlanCompiler} and the type classification it relies on.
 *
 * <p>Plans are derived once per class and cached in a {@code ClassValue}, so after the first
 * instance masking is a handful of {@code MethodHandle} invocations plus one constructor call.
 *
 * <p>The package is {@code @NullMarked}: every type in every signature here is non-null unless it
 * is annotated {@code @Nullable}. A {@code MemberAccessor} reads whatever the instance holds and a
 * {@code ValueRebuilder} is handed whatever masking produced, so both sides of those are nullable.
 */
@NullMarked
package ch.raph.datamask.infrastructure.reflect;

import org.jspecify.annotations.NullMarked;
