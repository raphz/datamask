# datamask-build-processor

**Works out every masking plan while the code is compiled, so the engine never has to reflect over a
class at runtime.**

```groovy
dependencies {
    implementation 'ch.raph.datamask:datamask-core'
    annotationProcessor 'ch.raph.datamask:datamask-build-processor'
}
```

That line is the whole wiring. No configuration, no code change, and nothing new on the runtime
classpath: each plan is emitted next to the type it describes and named after it — `Customer` gets
`Customer_MaskPlan` — and the compiler `DataMask.builder()` already uses asks for it by that name.

## What it replaces

Without it, `ReflectiveMaskPlanCompiler` derives a plan the first time it sees a class: every field,
every annotation on it, its getter, a `MethodHandle` unreflected per member, a constructor lookup.
The result is cached in a `ClassValue`, so the cost is paid once — but it is paid, and it is paid on
whichever request happens to be first. That is exactly where a p99 is measured.

| | Reflective | Generated |
|---|---|---|
| When the plan is derived | first request carrying the type | compilation |
| Reading a member | `MethodHandle.invoke` | a direct call |
| Rebuilding | unreflected constructor handle | `new Customer(...)` |
| Private members | reached with `privateLookupIn` | out of reach, so the type falls back |
| A module that opens nothing | `privateLookupIn` fails, silently down to `publicLookup()` | unaffected |
| GraalVM native image | needs reachability metadata for every masked type | needs none |

**What that is worth, measured.** `datamask-benchmarks` puts cold plan compilation for three types at
**30.2 µs reflectively against 8.5 µs generated — about 3.5×**. Steady-state masking through a warm
plan is **indistinguishable**: 1.27 µs reflective against 1.10 µs generated, both inside their own
error bars. So the row about reading a
member through a `MethodHandle` is true and does not matter — the JIT flattens the difference long
before it shows up in a throughput number.

Both warm figures halved when the detector gates landed, and neither moved relative to the other,
which is the expected shape: that work was content scanning, not member access. The cold pair has an
error bar of the same order as its own score — most iterations measure a class that has already been planned —
so trust the ratio and not the digits.

Sell this module on **startup and on GraalVM**, not on throughput. What it buys is the first request
carrying each type, and a native image that needs no reachability metadata. If your p99 is dominated
by masking, the thing to look at is content scanning, which costs an order of magnitude more than the
whole plan mechanism either way.

The generated source for a record is what you would have written by hand:

```java
@javax.annotation.processing.Generated("ch.raph.datamask.processor.plan.MaskPlanProcessor")
public final class Customer_MaskPlan implements GeneratedMaskPlan {

    public java.lang.Class<?> type() { return com.acme.Customer.class; }

    public MaskPlan plan() {
        List<MemberPlan> members = List.of(
                new MemberPlan(
                        "email",
                        com.acme.Email.class,
                        target -> ((com.acme.Customer) target).email(),
                        new MaskAction.Mask(new PiiDescriptor(PiiCategory.EMAIL, ..., Masker.class, ""))),
                ...);

        return new MaskPlan(
                com.acme.Customer.class,
                members,
                (original, values) -> new com.acme.Customer(
                        (com.acme.Email) values[0], (java.lang.String) values[1]));
    }
}
```

## The fallback is the design, not a safety net

`GeneratedMaskPlanCompiler` answers from the generated map and sends every miss to the reflective
compiler. That is what makes adoption a line in a build file rather than a migration: an application
masks types it did not compile — a DTO generated from an OpenAPI contract, a third-party model, a
module that has not been given the processor yet — and those keep working exactly as they did.

It is also what lets this processor be conservative. A type is refused whenever its plan could not be
written without reflection, and nothing is lost but the speed:

| Refused | Why |
|---|---|
| A private field with no accessor | A private lookup reaches it; generated source in the same package does not |
| No constructor a sibling class could call | Neither an all-arguments one matching the field order nor a no-argument one with every field writable |
| A generic type | The plan would have to rebuild through a raw type; the runtime already works on the raw class |
| An inner, local or anonymous class | Every constructor takes an enclosing instance, or there is no name to generate against |
| A private type | A generated class in the same package could not name it |

`-Adatamask.plan.verbose=true` prints one note per refused type saying which of these it was. It is
off by default because none of them is a mistake — `datamask-check-processor` is what has an opinion
about whether a type carrying `@PII` ought to be shaped that way at all.

## Which types get a plan

Every type in the compilation that declares `@PII` or `@NoMask`, **and every type that holds one** —
transitively, through fields, record components, arrays and generic type arguments.

The second half is what makes it worth doing. A wrapper like `Portfolio` carries no annotation of its
own, but it is the object an application actually passes to `mask()`; leaving it out would put
reflection back on the first request for exactly the type that gets masked most.

## Reading a field rather than its getter

Where both are reachable the generated plan reads the **field**, because the field is what the
runtime reads. A getter that computes something instead of returning its field would make the two
disagree. A getter is used only when the field is private, which is the one case where there is
nothing else to read.

## Policy overrides switch it off

`PolicyOverrides` exists at runtime and a generated plan was resolved before it. Answering from one
while overrides are configured would silently ignore them — and an ignored override is a value the
deployment asked to mask coming out unmasked, which is the one class of bug this library must not
have. So `DataMask.builder()` hands back the plain reflective compiler as soon as overrides are
present. Slower, and correct.

## How it is kept honest

Two compilers deriving the same answer by different routes is a thing that decays silently. A
generated plan that quietly disagreed would not throw and would not look wrong; it would mask one
field differently from what the annotation says, on whichever deployment happened to have the
processor on its path.

So `PlanEquivalenceTest` compiles the shared banking domain with the processor attached, loads the
plans it emitted, and compares them **member by member — same names, same order, same `MaskAction`**
— against what `ReflectiveMaskPlanCompiler` derives for the very same classes. Then it masks the
same fixtures through both and compares the results. `GeneratedSourceTest` reads the emitted source
as text and asserts the words `java.lang.reflect`, `MethodHandle`, `Class.forName` and
`setAccessible` do not appear in it, because behaviour alone cannot demonstrate that.

`LeafTypes` is the compile-time twin of the runtime's type classification and is where a divergence
would hide. It has no way of sharing code with the original — one answers about a `Class`, the other
about a `TypeMirror` — so the equivalence test is what stands in for that.

## One thing generation does not get to change

`PiiDescriptor`'s compact constructor forces `keep = 0` for a category in
`PiiCategory.neverPartiallyReveal()`. The generated code therefore calls that constructor with the
annotation's arguments rather than emitting resolved fields:

```java
new PiiDescriptor(PiiCategory.CARD_VERIFICATION_VALUE, Sensitivity.HIGH, MaskStrategy.AUTO, 3, ...)
//                                                                                          ^ becomes 0
```

Writing the fields directly would have been simpler and would have produced a card verification value
with a `keep` the runtime refuses — a mask weaker in generated code than in reflected code, which is
the only difference between the two that would actually matter.

## Incremental builds

The processor declares itself **isolating** to Gradle, which it can only be because there is no
index. Each plan is written from one type, named after it, and declares that type as its single
originating element — so Gradle knows which source file each generated plan belongs to and can
regenerate or delete exactly that one.

This was aggregating while the processor wrote a `META-INF/services` file listing every plan, and
that was a correctness problem rather than a performance one. `Filer` cannot append to a resource, so
the index had to be rewritten whole on every build — while an incremental build shows the processor
only the sources that changed. It would rewrite the index with a fraction of the plans in it and put
every untouched type silently back on the reflective path, worst of all for the wrapper types nobody
edits and everything masks. Looking a plan up by name shares no state between two types, so there is
nothing left for an incremental round to truncate.

Plans registered through `ServiceLoader` are still honoured and still take precedence, so a
hand-written `GeneratedMaskPlan` keeps working.

## Non-goals

**It does not replace `datamask-check-processor`.** That one rejects `@PII` usage the engine cannot
carry out; this one replaces the reflection. Both read the same annotations, neither claims them, and
they are meant to sit on the annotation processor path together.

**It does not fail a build.** Generation is an optimisation, and an optimisation that refuses to
compile is worse than the thing it replaced. The one diagnostic it emits is a warning, for a plan it
could not write to disk.
