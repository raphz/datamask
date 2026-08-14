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

ch.raph.datamask.<framework>       (one flat package per integration module)
    jdbc/     MaskingDataSource, SqlExceptionSanitizer (public); SqlErrorText,
              PostgresErrorSanitizer, JdbcMasking, JdbcProxies, BoundParameters (internal)
```

An integration module keeps a **flat package** and exposes as little as possible: the one or two
types an application touches are public, the rest is package-private. It depends on
`datamask-core` and on its own framework, never on another integration module.

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

## API surface reference

Every signature an integration module needs. **This is here so you do not have to open
`datamask-core` to write one.** Verified against the source; if something contradicts the code, the
code wins and this table is stale.

### `DataMask` — `ch.raph.datamask.application`

```java
static Builder builder()
static DataMask withDefaults()          // ephemeral key; tests and local development only
<T> T mask(T value)                     // masked copy, same type
String maskText(CharSequence text)      // mask PII inside free text
List<PiiFinding> scan(CharSequence)     // report without changing
String pseudonymize(String value)       // same surrogate HASH would produce
Optional<String> detokenize(String token)
MaskingEngine engine()                  // what an integration module actually holds
MaskingPolicy policy()
TokenVault vault()
```

`Builder`: `secret(String)`, `key(MaskKey)`, `policy(MaskingPolicy)`, `vault(TokenVault)`,
`observer(MaskingObserver)`, `overrides(PolicyOverrides)`, `detectors(List<PiiDetector>)`,
`detector(PiiDetector)`, `masker(MaskStrategy, Masker)`, `masker(Masker)`, `build()`.

### `MaskingEngine` — the integration entry point

```java
Object mask(Object value)
String maskText(CharSequence text, String path)   // returns the SAME String when nothing matched
Object maskDeclared(Object value, PiiDescriptor descriptor, Class<?> declaredType, String path)
MaskingPolicy policy()
TextSanitizer sanitizer()
MaskPlanCompiler compiler()
MaskingObserver observer()
```

An integration module takes a `MaskingEngine`, not a `DataMask` — offer both constructors and have
the `DataMask` one delegate to `dataMask.engine()`. That is what `DataMaskModule` and
`MaskingDataSource` both do.

`maskDeclared` is the one to reach for when the integration already knows what a value is; `maskText`
is for when it does not and the detectors have to decide.

### `TextSanitizer`

```java
String sanitize(CharSequence text, String path)   // same instance when nothing matched
List<PiiFinding> scan(CharSequence text)          // document order, overlaps removed
Optional<PiiCategory> classify(CharSequence text) // Some only when ONE detector matches end to end
```

`classify` is what an integration uses to decide what a whole value is — a bind parameter, a span
attribute, a Kafka header. `sanitize` is for text with prose around the PII.

### Domain types

```java
record MaskingPolicy(Sensitivity threshold, FailureMode failureMode, String redactionPlaceholder,
                     int maxDepth, int maxCollectionElements,
                     boolean scanUnannotatedText, boolean maskMapKeys)
    static strict() / relaxed(); applies(Sensitivity);
    withThreshold / withFailureMode / withScanUnannotatedText / withRedactionPlaceholder

record PiiDescriptor(PiiCategory category, Sensitivity sensitivity, MaskStrategy strategy,
                     int keep, char padding, String replacement,
                     Class<? extends Masker> maskerType, String purpose)
    static from(PII) / redacting(PiiCategory)     // `redacting` forces REDACT
    // compact constructor forces keep = 0 for category.neverPartiallyReveal()

record PiiFinding(int start, int end, PiiCategory category, String detector, boolean confident)
    length(); overlaps(PiiFinding)

record PolicyOverrides(Map<String, PiiDescriptor> byMember, Map<String, PiiDescriptor> byType)
    static none(); forMember(Class<?>, String); forType(Class<?>); isEmpty()

sealed interface MaskAction { Mask(PiiDescriptor) | Descend() | Keep() | Drop() }

interface PiiDetector      { String name(); List<PiiFinding> detect(CharSequence); }   // NOT functional
interface Pseudonymizer    { String pseudonymize(String); }
interface TokenVault       { String tokenize(String, PiiCategory); Optional<String> detokenize(String); }
interface MaskContextFactory { MaskContext create(PiiDescriptor, MaskStrategy, String path, Class<?>); }

enum FailureMode { REDACT, THROW, PASS_THROUGH }
enum Sensitivity { ... atLeast(Sensitivity) }
```

`MaskingObserver` — all four methods `default`, so implement only what you need:
`onMasked(String path, PiiCategory, MaskStrategy)`,
`onUnannotatedPii(String path, PiiCategory, String detector)`, `onFailure(String path, Throwable)`,
`onDepthLimitExceeded(String path)`. `MaskingObserver.NOOP` is the default.

### `datamask-api` — what a custom masker sees

```java
interface Masker { Object mask(Object value, MaskContext context);
                   default boolean supports(Class<?> type) { return true; } }

interface MaskContext { PiiCategory category(); Sensitivity sensitivity(); MaskStrategy strategy();
                        int keep(); char padding(); String replacement(); String path();
                        Class<?> declaredType(); String redactionPlaceholder();
                        String pseudonymize(String); String tokenize(String); }
```

`@PII` attributes: `strategy` (AUTO), `category` (UNSPECIFIED), `sensitivity` (HIGH), `masker`
(`Masker.class`), `keep` (-1 = category default), `padding` (`'*'`), `replacement` (`""`), `purpose`
(`""`). `@NoMask` requires a `justification`.

`MaskKey`: `ofSecret(String)` (rejects under 16 bytes), `of(byte[])`, `ephemeral()`, `spec()`,
`isEphemeral()`.

## Writing an integration module

The same five decisions come up every time, and the answers are already settled.

**1. Take a `MaskingEngine`.** Two constructors, `DataMask` delegating to `engine()`. Hold nothing
else; the engine carries the policy, the observer and the sanitiser.

**2. Map `AUTO` and `SCAN` to `REDACT` whenever you build a `PiiDescriptor` yourself.** Neither
resolves to anything at an integration boundary, and `SCAN` would re-enter the scanner and not
terminate. `TextSanitizer.maskSpan` and `JdbcMasking.maskText` both do this; copy it.

```java
MaskStrategy strategy = category.defaultStrategy();
if (strategy == MaskStrategy.AUTO || strategy == MaskStrategy.SCAN) {
    strategy = MaskStrategy.REDACT;
}
```

**3. Pass a path, and make it say where the value came from.** `"jdbc:error/detail"`,
`"jdbc:param/2"`, `"kafka:header/x"`. The path is what reaches the observer, and
`onUnannotatedPii(path, ...)` is only actionable if the path identifies the site.

**4. Preserve the no-change short-circuit.** `maskText` and `sanitize` return the *same instance*
when nothing matched. Integrations should do the same at their own boundary — return the original
object when there was nothing to remove. `SqlExceptionSanitizer` returns the very same exception, so
an ordinary error reaches the application exactly as the driver threw it.

**5. Fail closed at the boundary too.** Catch your own failures, report `observer.onFailure(path, e)`
and emit `policy().redactionPlaceholder()` — never the value you failed to mask. Honour
`FailureMode.THROW` if a test would want the bug surfaced.

For a value of unknown provenance, `PiiCategory.UNSPECIFIED` with `MaskStrategy.REDACT` is the
fail-closed pair; that is what a masked database row value is reported as.

Optional third-party dependencies use `compileOnly` plus a `Class.forName` guard, with the code that
touches the optional type in a **separate class** so it loads only once the guard has passed —
`SqlExceptionSanitizer` / `PostgresErrorSanitizer` is the worked example.

**6. Write the module's `README.md`.** It is part of finishing the module, not a follow-up. The root
README stays high level and links to it; module-specific explanation never goes at the root. See the
`datamask-build` skill for what belongs in it.

## Deliberate non-goals

- No static "does this type carry PII" analysis. Declared types lie (`Object`, interfaces); the
  engine uses the runtime class and relies on the plan cache instead.
- No build-time code generation yet. `MaskPlanCompiler` is a port precisely so a generated
  implementation can slot in later for GraalVM native images.
- No caching of value → pseudonym. It would speed up repeated ids but means holding PII in memory.
