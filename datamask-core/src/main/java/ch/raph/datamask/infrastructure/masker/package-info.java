/**
 * The built-in masking algorithms, one class per {@code MaskStrategy}.
 *
 * <p>A new masking algorithm is a class here plus a line in {@code MaskerRegistry}. Every masker
 * that reveals part of its input repeats the {@code neverPartiallyReveal()} guard as its first
 * line, rather than trusting the caller to have hardened the strategy already.
 *
 * <p>The package is {@code @NullMarked}: every type in every signature here is non-null unless it
 * is annotated {@code @Nullable}. {@code Masker.mask} returns {@code @Nullable} because dropping
 * the value is a legitimate masking outcome — {@code NullifyMasker} does nothing else.
 */
@NullMarked
package ch.raph.datamask.infrastructure.masker;

import org.jspecify.annotations.NullMarked;
