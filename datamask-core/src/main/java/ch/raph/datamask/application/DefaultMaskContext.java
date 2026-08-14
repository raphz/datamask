package ch.raph.datamask.application;

import ch.raph.datamask.api.MaskContext;
import ch.raph.datamask.api.MaskStrategy;
import ch.raph.datamask.api.PiiCategory;
import ch.raph.datamask.api.Sensitivity;
import ch.raph.datamask.domain.MaskingException;
import ch.raph.datamask.domain.MaskingPolicy;
import ch.raph.datamask.domain.PiiDescriptor;
import ch.raph.datamask.domain.Pseudonymizer;
import ch.raph.datamask.domain.TokenVault;

record DefaultMaskContext(
        PiiDescriptor descriptor,
        MaskStrategy resolvedStrategy,
        String path,
        Class<?> declaredType,
        MaskingPolicy policy,
        Pseudonymizer pseudonymizer,
        TokenVault vault)
        implements MaskContext {

    @Override
    public PiiCategory category() {
        return descriptor.category();
    }

    @Override
    public Sensitivity sensitivity() {
        return descriptor.sensitivity();
    }

    @Override
    public MaskStrategy strategy() {
        return resolvedStrategy;
    }

    @Override
    public int keep() {
        return descriptor.effectiveKeep();
    }

    @Override
    public char padding() {
        return descriptor.padding();
    }

    @Override
    public String replacement() {
        return descriptor.replacement();
    }

    @Override
    public String redactionPlaceholder() {
        return policy.redactionPlaceholder();
    }

    @Override
    public String pseudonymize(String value) {
        return pseudonymizer.pseudonymize(value);
    }

    @Override
    public String tokenize(String value) {
        if (vault == null) {
            // Degrading to an irreversible value would silently break every caller that expects to
            // exchange the token back, so this is a configuration error rather than a fallback.
            throw new MaskingException(path, "TOKENIZE was requested but no TokenVault is configured", null);
        }
        return vault.tokenize(value, descriptor.category());
    }
}
