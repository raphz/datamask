/**
 * Annotations and SPI contracts for DataMask.
 *
 * <p>This module has no dependencies at all, by design. A domain module can depend on it to
 * declare {@link ch.raph.datamask.api.PII} on its records without taking on the masking engine,
 * a reflection library, or a logging framework.
 */
package ch.raph.datamask.api;
