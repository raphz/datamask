package ch.raph.datamask.plan.downstream;

import ch.raph.datamask.plan.upstream.Contact;

/**
 * A wrapper that declares nothing and holds something annotated from another module.
 *
 * <p>This is the object an application hands to {@code mask()}, and the one a plan is most worth
 * having for. Its member's annotation lives in a dependency, so the only way to know it is worth
 * planning is to resolve {@link Contact} and read it there.
 */
public record Envelope(String reference, Contact contact) {}
