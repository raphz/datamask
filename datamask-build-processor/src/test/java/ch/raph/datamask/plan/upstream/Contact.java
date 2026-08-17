package ch.raph.datamask.plan.upstream;

import ch.raph.datamask.api.PII;
import ch.raph.datamask.api.PiiCategory;

/**
 * The annotated half of a two-module domain, compiled on its own.
 *
 * <p>It stands in for the types an application keeps in a domain module: the module that holds
 * {@link ch.raph.datamask.plan.downstream.Envelope} depends on this one and sees it as a jar, which
 * is the layout the processor has to work in and the one where a wrapper used to get no plan at all.
 */
public record Contact(@PII(category = PiiCategory.EMAIL) String email, String country) {}
