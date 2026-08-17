/**
 * Content detection: recognising PII in text nobody annotated.
 *
 * <p>A new identifier to recognise is a {@code PiiDetector} here plus an entry in
 * {@code Detectors.defaults()} — and the order of that list is detector priority, because
 * overlapping findings are resolved earliest-start, then longest, then by it.
 *
 * <p>The package is {@code @NullMarked}: every type in every signature here is non-null unless it
 * is annotated {@code @Nullable}.
 */
@NullMarked
package ch.raph.datamask.infrastructure.detect;

import org.jspecify.annotations.NullMarked;
