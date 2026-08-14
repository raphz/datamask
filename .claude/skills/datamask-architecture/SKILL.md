---
name: datamask-architecture
description: How DataMask is structured internally — clean-architecture layering, every key type and where it lives, how the masking engine walks an object graph, how AUTO strategy resolution works, and the extension points. Triggers on — adding a masker, detector or strategy, changing the engine, adding an integration module, "where does this code go", "how does masking work", debugging a masking result, anything touching datamask-core or datamask-api.
---

# DataMask — internal architecture

## Layering

Clean architecture, enforced by package. **Dependencies point inward.**

```
ch.raph.datamask.api              (module datamask-api — no dependencies at all)
    PII, NoMask, MaskStrategy, PiiCategory, Sensitivity, Masker, MaskContext

ch.raph.datamask.domain           (module datamask-core — no framework dependencies)
    MaskPlan, MemberPlan, MaskAction, MemberAccessor, ValueRebuilder, PiiDescriptor,
    MaskingPolicy, FailureMode, MaskingObserver, PolicyOverrides, MaskingException,
    Pseudonymizer (port), TokenVault (port), PiiDetector (port), PiiFinding

ch.raph.datamask.application      (use cases and orchestration)
    DataMask (facade + Builder), MaskingEngine, TextSanitizer, MaskerRegistry,
    MaskPlanCompiler (port), MaskContextFactory, DefaultMaskContext, Coercion

ch.raph.datamask.infrastructure   (adapters)
    reflect/  ReflectiveMaskPlanCompiler, Types
    masker/   Redact, Partial, Hash, Tokenize, Nullify, Email, Name, Iban, Pan,
              Phone, IpAddress, DateGeneralize, FormatPreserving, Masks (helpers)
    detect/   RegexDetector, Detectors (the default set), Checksums
    crypto/   MaskKey, HmacPseudonymizer
    vault/    InMemoryTokenVault
```

Where new code goes: a new masking algorithm → `infrastructure/masker`. A new identifier to
recognise → `infrastructure/detect` (add to `Detectors.defaults()`). A new concept in the masking
vocabulary → `domain`. A new integration → its own module depending on `datamask-core`.

## The engine, concretely

`MaskingEngine.mask(Object)` walks the graph and returns a **masked copy**; the original is never
mutated.

**Plan compilation.** `ReflectiveMaskPlanCompiler` derives a `MaskPlan` per class and caches it in a
`ClassValue` — chosen over a `Map<Class, ?>` because it lets a class and its plan be collected
together when a classloader goes away. After the first instance, masking is a handful of
`MethodHandle` invocations plus one constructor call.

Each `MemberPlan` carries a `MaskAction`, a sealed interface with four cases:

- `Mask(PiiDescriptor)` — replace the value
- `Descend` — recurse; nested object, container, or an unannotated `CharSequence` that content
  scanning may still need to look at
- `Keep` — copy across untouched
- `Drop` — omit entirely

**Rebuilding.** Records use the canonical constructor. Beans use an all-arguments constructor whose
parameter types match field order (what Lombok's `@AllArgsConstructor` and Jackson's
`@ConstructorProperties` produce), else a no-argument constructor plus field writes. When neither
works the plan's rebuilder throws a `MaskingException` with a message telling the caller what to do.

**The no-change short-circuit is important.** If no member changed, the engine returns the *same
instance* and never calls the rebuilder. That is why a PII-free graph costs no allocation, and why
an unrebuildable type that happens to contain no PII still works.

**Traversal safety.** Cycle detection via an identity set with enter/exit scoping (a shared node in
a DAG is not a cycle). Depth bounded by `MaskingPolicy.maxDepth`. Collections and maps bounded by
`maxCollectionElements`; the tail is dropped, which discloses nothing. Map keys are masked only when
`maskMapKeys` is on, because masking them changes lookup semantics.

**Type coercion** (`Coercion.toDeclaredType`). Masking naturally produces text, but the member it
goes back into may be a `BigDecimal` or an `int`. Rather than refuse, the value becomes the type's
zero — `BigDecimal.ZERO`, `0`, `false`. This is what lets numeric PII be masked at all while keeping
the rebuilt object type-correct.

## AUTO strategy resolution

`MaskStrategy.AUTO` is the annotation default. It resolves in this order, and the order matters:

1. **Category default** — `PiiCategory.defaultStrategy()`.
2. **Type-level `@PII`** — resolved at *plan-compile* time: a bare `@PII Email email` defers to
   whatever the `Email` type declares. See `isBare()` in `ReflectiveMaskPlanCompiler`.
3. **Content detection** — `TextSanitizer.classify()` returns a category only when a detector
   matches the value end to end.
4. **`REDACT`** — the fallback. Never "pass through".

This chain is why `@PII Email email` produces email-shaped masking with no further configuration.

## Value objects

`Types.isSingleStringValueObject` recognises a record with exactly one `CharSequence` component —
the shape most DDD domains use for a wrapped identifier. The engine masks the *inner* string and
rebuilds, so `Email` stays an `Email`.

If the value object's constructor validates its input and rejects the masked string (an IBAN value
object checking mod-97 would), the failure is caught and the field becomes `null`. Lossy on purpose:
fail closed rather than disclose.

## Text scanning

`TextSanitizer` masks PII *inside* free-form text and leaves the prose readable. It covers what
annotations cannot: exception messages quoting a row, a payment reference the customer typed their
IBAN into, a support note, a prompt assembled from several sources.

Overlaps are resolved earliest-start, then longest, then detector priority — and detector priority
*is* the order of `Detectors.defaults()`, relying on a stable sort. Checksum-confirmed detectors are
listed first for that reason.

`sanitize()` returns the same `String` instance when nothing matched, which preserves the engine's
no-change short-circuit.

## Extension points

| Want to | Do this |
|---|---|
| Mask a proprietary format | Implement `Masker`, reference it with `@PII(masker = X.class)` |
| Replace a built-in strategy | `DataMask.builder().masker(MaskStrategy.IBAN, myMasker)` |
| Recognise a new identifier | Implement `PiiDetector` (or use `RegexDetector`), add via `.detector(...)` |
| Mask a class you cannot annotate | `PolicyOverrides`, keyed `Type` or `Type#member` |
| Collect metrics / audit | Implement `MaskingObserver` |
| Reversible surrogates | Implement `TokenVault` |

`MaskContextFactory` exists so the engine and the text sanitiser produce contexts backed by the
*same* key and vault — otherwise a pseudonym in a masked field would not match the same value
spotted inside a log message.

## Deliberate non-goals

- No static "does this type carry PII" analysis. Declared types lie (`Object`, interfaces); the
  engine uses the runtime class and relies on the plan cache instead.
- No build-time code generation yet. `MaskPlanCompiler` is a port precisely so a generated
  implementation can slot in later for GraalVM native images.
- No caching of value → pseudonym. It would speed up repeated ids but means holding PII in memory.
