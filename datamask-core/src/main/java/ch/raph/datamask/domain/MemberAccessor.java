package ch.raph.datamask.domain;

/**
 * Reads one member of an object. Implemented over {@code MethodHandle}s so that the cost after the
 * first call is comparable to a direct field read.
 */
@FunctionalInterface
public interface MemberAccessor {

    Object get(Object target) throws Throwable;
}
