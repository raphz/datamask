/**
 * The keyed cryptography behind pseudonymisation: the masking key and the HMAC pseudonymizer.
 *
 * <p>The pseudonym format carries the key id on purpose. Without it, rotating a secret would turn
 * every pseudonym issued beforehand into an unjoinable stranger, with no error and nothing in a log
 * to explain it.
 *
 * <p>The package is {@code @NullMarked}: every type in every signature here is non-null unless it
 * is annotated {@code @Nullable}.
 */
@NullMarked
package ch.raph.datamask.infrastructure.crypto;

import org.jspecify.annotations.NullMarked;
