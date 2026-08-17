/**
 * Compile-time validation of {@code @PII} usage.
 *
 * <p>{@link ch.raph.datamask.processor.PiiProcessor} is the whole public surface: put the module on
 * the annotation processor path and the mistakes that would otherwise surface as a masking
 * exception in production — a custom masker the engine cannot instantiate, a class it cannot
 * rebuild — stop the build instead. The two findings the runtime silently corrects, a blank
 * {@code @NoMask} justification and a {@code keep} on a category that is never partially revealed,
 * are reported as warnings, because there the danger is a declaration that no longer describes what
 * the code does.
 *
 * <p>A new check is a class in this package with a {@code check} method and one line in the
 * processor. Diagnostics name the path and the type and never a value, the same rule
 * {@code MaskingException} follows.
 *
 * <p>The package is {@code @NullMarked}: every type in every signature here is non-null unless it
 * is annotated {@code @Nullable}.
 */
@NullMarked
package ch.raph.datamask.processor;

import org.jspecify.annotations.NullMarked;
