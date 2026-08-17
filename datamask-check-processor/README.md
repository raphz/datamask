# datamask-check-processor

**Turns the `@PII` mistakes that only surface in production — or never surface at all — into
compile errors.**

```groovy
dependencies {
    implementation 'ch.raph.datamask:datamask-api'
    annotationProcessor 'ch.raph.datamask:datamask-check-processor'
}
```

Nothing else changes. No configuration, no generated code, and no dependency added to what is
shipped: the processor runs on the annotation processor path and disappears from the artifact.

## What it checks

| Check | Severity | What would otherwise happen |
|---|---|---|
| `@PII` on a member that also carries `@NoMask` | error | The exemption wins and the value is copied across in clear text |
| `@PII(masker = X.class)` where `X` cannot be instantiated | error | The masker never runs, and the field comes out as `****` |
| A class holding `@PII` that the engine cannot rebuild | error | `MaskingException` the first time an instance is masked |
| `@NoMask` with a blank justification | error | An unexplained exemption reaches review looking deliberate |
| `@PII` on a static field | warning | Neither plan compiler ever reads it, so the annotation does nothing |
| `keep` on a category that is never partially revealed | warning | The runtime forces `keep` to 0 and the declaration keeps claiming otherwise |
| `@NoMask` justified with `TODO`, `n/a`, `-` … | warning | Same as blank, one step further from being noticed |

Diagnostics name the path and the type — `Customer.email`, `banking.TariffCode` — and
never a value. That is the rule `MaskingException` follows, and at compile time there is no value to
name anyway; keeping to it is what stops a future check from inventing one.

## An annotation that reads as protection and provides none

`@PII` and `@NoMask` on the same member is the worst declaration this library allows. Both plan
compilers consult the exemption first, so the value is copied across untouched — and the source says
the opposite, in the one place a reviewer or a compliance report goes looking. It is an error, and it
is found on the getter as well as on the field, because the runtime reads both:

```java
@PII private String email;

@NoMask(justification = "already redacted upstream")
public String getEmail() { return email; }
```

`@PII` on a **static** field is the quieter version of the same thing. Both compilers read instance
members only — a static field is not part of an instance's masked copy — so the annotation does
nothing at all. That one is a warning: nothing is disclosed that was not already outside the object
graph, but the declaration is a lie somebody will believe.

## Why a custom masker that cannot be built is the worst of them

`@PII(masker = TariffCode.class)` is instantiated reflectively, with
`getDeclaredConstructor().newInstance()`. When that fails — the class is package-private, its
constructor takes arguments, it is an inner class, it is abstract — the engine **fails closed**: the
field is replaced with the redaction placeholder and `MaskingObserver.onFailure` is told.

So the output still looks masked. Every test asserting "the raw value is absent" still passes. The
one thing that changed is that the masker the developer wrote has never once run, and nothing in the
output says so. That is precisely the class of failure this library exists to prevent, and there is
no better place to catch it than the compiler.

The processor accepts the same two ways out the engine does: give the class a public no-argument
constructor, or hand the instance to `DataMask.builder().masker(new TariffCode(...))` and keep the
annotation pointing at it.

## Why the rebuild check is deliberately local

The engine masks by building a copy, so a class with something to mask needs a constructor: the
canonical one if it is a record, an all-arguments constructor matching the field order — what
Lombok's `@AllArgsConstructor` and Jackson's `@ConstructorProperties` emit — or a no-argument
constructor it can follow with field writes. With neither, masking throws. The diagnostic therefore
names the constructor that is missing, not merely the fact that one is:

```
datamask: banking.Customer holds @PII but cannot be rebuilt once its values are masked: it has no
no-argument constructor and no constructor Customer(String, int) matching the field order
(email, age). Add one of the two, make it a record, or mask it at serialisation time with
datamask-jackson.
```

"Followed by field writes" is meant literally, and it is where the check used to be wrong: the
runtime writes each field with `Lookup.unreflectSetter`, which refuses a **final** field outright. A
private lookup does not help and neither does a setter, because a setter cannot assign one either. So
a class with a no-argument constructor and final fields reads as rebuildable and is not, and every
instance of it fails on the first mask. The diagnostic names the fields that are final.

In the other direction the check follows what the engine actually does: a constructor whose
parameters *name* the fields is accepted even when they are in a different order, because that is
what `ReflectiveMaskPlanCompiler` matches by name and permutes. Reporting one of those would fail a
build over code that masks perfectly.

The check fires on a class that declares `@PII` **itself**. It never tries to work out whether some
type three levels down carries PII, because declared types lie — `Object`, an interface, a subclass
— and the library's standing position is that only the runtime class can answer that. Four cases are
therefore passed over in silence rather than guessed at:

- **abstract classes**, because the class the engine rebuilds is a concrete subclass, and that is
  where the constructor has to be;
- **Lombok-annotated classes**, whose constructors are generated during annotation processing and
  may not be in the element model yet when this runs — reporting them would be a false alarm on
  code that works;
- **classes with no instance fields**, which the engine treats as opaque;
- **local and anonymous classes**.

## Severity, and adopting the processor on an existing codebase

Findings that break at runtime are errors. Findings the runtime silently corrects are warnings —
there the danger is not a leak but a declaration that has stopped describing what the code does,
which is what a reviewer or a compliance report is reading.

`-Adatamask.strict=false` reports the errors as warnings too:

```groovy
tasks.withType(JavaCompile).configureEach {
    options.compilerArgs << '-Adatamask.strict=false'
}
```

That is for the first build on a codebase that has been annotating for a year, so the findings can
be read and fixed in order rather than one at a time. It is not a setting to leave on.

## Incremental builds

The processor declares itself **isolating** in
`META-INF/gradle/incremental.annotation.processors`. Without that declaration Gradle assumes the
worst of any processor on the annotation path and recompiles every source file in the project on
every change — a cost paid by every build that adds this module, and by nothing in this repository,
which is exactly why it could go unnoticed.

The contract is that every decision comes from the annotated element and what is reachable from it.
All the checks qualify: the masker check reads the class the annotation names, the rebuild check
reads the owning class and its superclass chain, and the rest read attributes of the annotation
itself. Nothing consults another source file in the round, and nothing is generated at all, so there
is no output an incremental round could rewrite with half of it missing.

## Deliberate non-goals

- **No generated code.** That is
  [`datamask-build-processor`](../datamask-build-processor/README.md), which writes the masking plans
  this module only ever validates. The two read the same annotations, neither claims them, and they
  are meant to sit on the annotation processor path together.
- **No "does this type carry PII" analysis.** Same reason the engine has none.
- **No check on where `@PII` is placed**, beyond the static-field case above. The engine reads fields
  and record components, so an annotation on a constructor parameter does nothing — but javac copies
  a record component's annotation onto its constructor parameter, so the deliberate ones and the
  ineffective ones are indistinguishable by the time a processor sees them.

  Its copies onto the *field* and the *accessor* are told apart, though, and that matters: a
  declaration is only treated as one of javac's copies when the record component carries the same
  annotation. A hand-written accessor with a `@NoMask` its component does not have was written by
  somebody on purpose, and it used to be skipped in silence — blank justification and all.
