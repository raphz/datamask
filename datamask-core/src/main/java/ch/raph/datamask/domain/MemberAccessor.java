package ch.raph.datamask.domain;

import org.jspecify.annotations.Nullable;

/**
 * Reads one member of an object. Implemented over {@code MethodHandle}s so that the cost after the
 * first call is comparable to a direct field read.
 */
@FunctionalInterface
public interface MemberAccessor {

    /** The member's value, which is {@code null} whenever the object holds a null there. */
    @Nullable
    Object get(Object target) throws Throwable;
}
