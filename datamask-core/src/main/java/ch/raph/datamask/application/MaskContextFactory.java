package ch.raph.datamask.application;

import ch.raph.datamask.api.MaskContext;
import ch.raph.datamask.api.MaskStrategy;
import ch.raph.datamask.domain.PiiDescriptor;

/**
 * Builds the context handed to a {@link ch.raph.datamask.api.Masker}.
 *
 * <p>The engine and the text sanitiser both need one, and both must produce contexts backed by the
 * same key and the same vault, or pseudonyms would stop matching between a masked field and the
 * same value spotted inside a log message.
 */
@FunctionalInterface
public interface MaskContextFactory {

    MaskContext create(PiiDescriptor descriptor, MaskStrategy resolvedStrategy, String path, Class<?> declaredType);
}
