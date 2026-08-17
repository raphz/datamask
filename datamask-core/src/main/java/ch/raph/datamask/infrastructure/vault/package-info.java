/**
 * Token vault adapters: what a reversible surrogate is resolved against.
 *
 * <p>The default refuses. An in-memory vault is opt-in and bounded, because {@code TOKENIZE}
 * appearing to work while raw PII accumulates in a heap map, with a {@code detokenize} any caller
 * can reach, is worse than it failing.
 *
 * <p>The package is {@code @NullMarked}: every type in every signature here is non-null unless it
 * is annotated {@code @Nullable}. {@code TokenVault.detokenize} reports "no such token" with an
 * empty {@code Optional}, never with {@code null}.
 */
@NullMarked
package ch.raph.datamask.infrastructure.vault;

import org.jspecify.annotations.NullMarked;
