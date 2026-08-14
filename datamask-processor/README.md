# datamask-processor

**Turns the `@PII` mistakes that only surface in production — or never surface at all — into
compile errors.**

```groovy
dependencies {
    implementation 'ch.raph.datamask:datamask-api'
    annotationProcessor 'ch.raph.datamask:datamask-processor'
}
```

Nothing else changes. No configuration, no generated code, and no dependency added to what is
shipped: the processor runs on the annotation processor path and disappears from the artifact.

## What it checks

| Check | Severity | What would otherwise happen |
|---|---|---|
| `@PII(masker = X.class)` where `X` cannot be instantiated | error | The masker never runs, and the field comes out as `****` |
| A class holding `@PII` that the engine cannot rebuild | error | `MaskingException` the first time an instance is masked |
| `@NoMask` with a blank justification | error | An unexplained exemption reaches review looking deliberate |
| `keep` on a category that is never partially revealed | warning | The runtime forces `keep` to 0 and the declaration keeps claiming otherwise |
| `@NoMask` justified with `TODO`, `n/a`, `-` … | warning | Same as blank, one step further from being noticed |

Diagnostics name the path and the type — `Customer.email`, `banking.TariffCode` — and
never a value. That is the rule `MaskingException` follows, and at compile time there is no value to
name anyway; keeping to it is what stops a future check from inventing one.

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

## Deliberate non-goals

- **No generated code.** `MaskPlanCompiler` is a port so that a build-time plan compiler can slot in
  later for GraalVM native images; this module validates, and validating is all it does.
- **No "does this type carry PII" analysis.** Same reason the engine has none.
- **No check on where `@PII` is placed.** The engine reads fields and record components, so an
  annotation on a constructor parameter does nothing — but javac copies a record component's
  annotation onto its constructor parameter, so the deliberate ones and the ineffective ones are
  indistinguishable by the time a processor sees them.
