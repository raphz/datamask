/**
 * The masking vocabulary: what a plan is, what a policy says, and the ports the engine is written
 * against.
 *
 * <p>This is the innermost layer. It knows the annotations in {@code ch.raph.datamask.api} and
 * nothing else — never the use cases, never an adapter — which is what keeps the vocabulary
 * independent of how masking is actually carried out.
 *
 * <p>The package is {@code @NullMarked}: every type in every signature here is non-null unless it
 * is annotated {@code @Nullable}. A {@code @Nullable} value in this package is almost always the
 * fail-closed outcome — a member that could not be read, rebuilt or masked becomes {@code null}
 * rather than staying raw — so it is a masked result, not an unknown one.
 */
@NullMarked
package ch.raph.datamask.domain;

import org.jspecify.annotations.NullMarked;
