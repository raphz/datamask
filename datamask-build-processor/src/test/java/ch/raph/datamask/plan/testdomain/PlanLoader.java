package ch.raph.datamask.plan.testdomain;

import java.lang.invoke.MethodHandles;

/**
 * Defines a generated plan into the domain's own package, which is where a real build puts it.
 *
 * <p>This exists because of how package access actually works. A generated plan reads a
 * package-private field with a plain field access, and the JVM allows that only when both classes
 * share a <em>runtime</em> package — same name <em>and</em> same classloader. In a real build they
 * do: the processor's output is compiled into the same jar as the domain. Loading the generated
 * class through a child classloader in a test would not, and the plan would fail with an
 * {@code IllegalAccessError} on a shape that works perfectly in production.
 *
 * <p>So the bytes go in here instead, through a lookup that already lives in the right package. The
 * {@code MethodHandles} in this file are scaffolding for the test and never appear in anything the
 * processor emits — {@code GeneratedSourceTest} is what holds that line.
 */
public final class PlanLoader {

    private PlanLoader() {}

    public static Class<?> define(byte[] bytecode) throws IllegalAccessException {
        return MethodHandles.lookup().defineClass(bytecode);
    }
}
