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
    generated/GeneratedMaskPlan (SPI implemented by generated code), GeneratedMaskPlanCompiler
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

**This layering is enforced, not just documented.** `datamask-architecture-tests` holds every module
on its test classpath and fails the build when the direction drifts: `datamask-api` on nothing but the
JDK, `domain` on the annotations only, `datamask-core` on no third-party library and no integration,
and each integration on the core plus its own framework — never on another integration, and never into
`infrastructure`. `application -> infrastructure` is the one allowed exception: `DataMask.Builder` and
`MaskerRegistry` are the composition root.

So **a module is not implemented until `ModuleDependencyTest` covers it.** Do this in the same change
as the module's first class — `everyModuleIsCoveredByARule()` compares the packages that actually have
bytecode against the rules, so the build goes red the moment code lands without a rule. Two ways in,
depending on what the module is:

- **A framework integration** gets a row in `integrations()`: the module name plus the framework
  packages its bytecode may reach for. Include packages it only uses through `compileOnly` — the
  bytecode still refers to them (`datamask-jdbc` and the PostgreSQL driver), and a facade the module
  writes through counts as its own (`org.slf4j..` for the logging integrations).
- **Anything that is not a framework integration** gets a rule of its own instead, so its allowance can
  be tighter than the shared integration one. The two processor modules are the example: an annotation
  processor sees `javax.lang.model` mirrors of `@PII` and never the runtime types, so its rule allows
  the JDK and `api` only — `domain` and `application` stay out. Add the module to
  `MODULES_WITH_THEIR_OWN_RULE` when you write the rule, or the coverage test still fails.

Do not add a row before the module has a class: a rule that matches no classes fails on its own.
Never widen an allowance to make the test pass without saying why in a comment beside it — that
allowance *is* the design, and the comments there are the record of which dependencies were argued for.

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
works the members stay in the plan (so a PII-free instance still short-circuits) but the rebuilder
throws a plain `IllegalStateException`, which the engine degrades per the failure policy — `null`
under REDACT, `MaskingException` under THROW. A type whose members cannot even be *read* gets
`MaskPlan.failed(type, reason)` — distinct from `opaque`, and treated as a structural failure
rather than passed through (`kafka`'s `RecordMasker` refuses the resulting `null` and fails the
send instead, because a null record value is a tombstone, not less information).

**The no-change short-circuit is important.** If no member changed, the engine returns the *same
instance* and never calls the rebuilder. That is why a PII-free graph costs no allocation, and why
an unrebuildable type that happens to contain no PII still works.

**Traversal safety.** Cycle detection via an identity **map** from original to copy, with enter/exit
scoping (a shared node in a DAG is not a cycle). The two halves differ on purpose:

- An **object** back-reference becomes `null`. Its copy does not exist until every member is masked,
  so there is nothing to point at — and it must never become the original, whose members are still
  raw.
- A **container** (array, collection, map) registers its copy *before* walking, so a back-reference
  points at the copy and the cycle is reproduced against masked values. This is not a nicety: without
  it a self-referential list unrolled to `maxDepth`, and one referencing itself twice unrolled
  exponentially and took the caller down with it.

Depth bounded by `MaskingPolicy.maxDepth`. Collections and maps bounded by `maxCollectionElements`;
the tail is dropped, which discloses nothing. A copy that refuses a masked element — `ArrayDeque` and
a naturally ordered `TreeSet` reject null, `ConcurrentHashMap` rejects it on both sides — drops that
element rather than failing the enclosing object. Map keys are masked when `maskMapKeys` is on, which
`strict()` enables and `relaxed()` does not; map *paths* are positional (`{0}`, `{0}{key}`), never
the key itself, because paths reach observers and exception messages.

**Container shape is preserved, because it has to be.** `newCollectionLike` maps `SortedSet`→`TreeSet`,
`Set`→`LinkedHashSet`, `List`→`ArrayList`, `Deque`/`Queue`→`ArrayDeque` (`List` is tested first —
`LinkedList` is both), and `newMapLike` maps `SortedMap`→`TreeMap`, `ConcurrentMap`→`ConcurrentHashMap`.
`Coercion` then fits whatever came back to the declared type. A shape mismatch is not cosmetic: it
fails the declared-type check and takes the entire member to `null`, which looks exactly like a
successful mask.

**`Optional` is masked through, not around.** `descend` returns the *same* `Optional` when its
contents did not change, and `maskLeaf` unwraps an annotated one, masks the value and re-wraps —
handing the wrapper to a masker produces text that cannot fit an `Optional` slot, and the coercion
that follows nulls the whole member. `OptionalInt`/`Long`/`Double` come back holding their zero.

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

Whatever was resolved is then **hardened** (`MaskingEngine.hardened`): a `neverPartiallyReveal()`
category refuses any strategy that would show part of the value — only REDACT, HASH, TOKENIZE and
NULLIFY survive; everything else becomes REDACT. Each revealing masker also carries the same guard
as its first line, and `PiiDescriptor`'s compact constructor pins those categories to
`Sensitivity.CRITICAL` so no policy threshold can switch their masking off.

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
<T> T mask(T value)                     // masked copy, same type; null in, null out
Object maskValue(Object, PiiCategory[, String path])   // one value whose category you already know
String maskText(CharSequence text[, String path])      // mask PII inside free text
List<PiiFinding> scan(CharSequence)     // report without changing; empty for null/empty text
String pseudonymize(String value)       // same surrogate HASH would produce
Optional<String> detokenize(String token)
MaskingEngine engine()                  // what an integration module actually holds
MaskingPolicy policy()
TokenVault vault()
```

`Builder`: `secret(String)`, `key(MaskKey)`, `policy(MaskingPolicy)`, `vault(TokenVault)`,
`observer(MaskingObserver)`, `overrides(PolicyOverrides)`, `detectors(List<PiiDetector>)`,
`detector(PiiDetector)`, `detectorFirst(PiiDetector)`, `masker(MaskStrategy, Masker)`,
`masker(Masker)`, `secret(char[])`, `previousKey(MaskKey)`, `previousSecret(String)`,
`compiler(MaskPlanCompiler)`, `build()`.

`detector` appends, so a built-in wins any tie; `detectorFirst` puts yours ahead of them. An
institution-specific format that happens to pass Luhn needs the second one or it is a payment card
forever.

### `MaskingEngine` — the integration entry point

```java
Object mask(Object value)
Object mask(Object value, String rootPath)        // ALWAYS use this one from an integration
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

**Pass the root path.** `mask(value)` reports a structural failure at the root against the empty
string, so a rule keyed on the scheme cannot tell which integration lost a value — and the root is
the one site an integration otherwise cannot name. Every member path underneath is built from it,
so `kafka:value/payments` gives `kafka:value/payments.iban`.

### `TextSanitizer`

```java
String sanitize(CharSequence text, String path)          // undeclared text -> onUnannotatedPii
String sanitizeDeclared(CharSequence text, String path)  // FREEFORM_TEXT / SCAN -> onScanned
List<PiiFinding> scan(CharSequence text)          // document order, overlaps removed; empty for null
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
    complete wither set: withThreshold / withFailureMode / withScanUnannotatedText /
    withRedactionPlaceholder / withMaskMapKeys / withMaxDepth / withMaxCollectionElements

record PiiDescriptor(PiiCategory category, Sensitivity sensitivity, MaskStrategy strategy,
                     int keep, char padding, String replacement,
                     Class<? extends Masker> maskerType, String purpose)
    static of(PiiCategory) / from(PII) / redacting(PiiCategory)   // `redacting` forces REDACT
    complete wither set, one per component: withCategory / withSensitivity / withStrategy /
    withKeep / withPadding / withReplacement / withMasker / withPurpose
    // the canonical constructor is NOT the API — build with of(...) and refine with withers
    // compact constructor forces keep = 0 for category.neverPartiallyReveal()

record PiiFinding(int start, int end, PiiCategory category, String detector, boolean confident)
    length(); overlaps(PiiFinding)

record PolicyOverrides(Map<String, PiiDescriptor> byMember, Map<String, PiiDescriptor> byType,
                       Set<String> dropped)
    static none() / builder(); two-arg constructor still means "no drops"
    Builder: member(Class<?>, String, PiiDescriptor) / type(Class<?>, PiiDescriptor) / drop(Class<?>, String)
    forMember(Class<?>, String); forType(Class<?>); drops(Class<?>, String); isEmpty()

sealed interface MaskAction { Mask(PiiDescriptor) | Descend() | Keep() | Drop() }
    // Drop is what PolicyOverrides.drop compiles to, and it is decided BEFORE @NoMask:
    // the annotation is the author's claim, the override is the deployment disagreeing.

class MaskingException  // final; build with atPath(path, message[, cause]) / withoutPath(message)

interface PiiDetector      { String name(); List<PiiFinding> detect(CharSequence); }   // NOT functional
interface Pseudonymizer    { String pseudonymize(String); }
interface TokenVault       { String tokenize(String, PiiCategory); Optional<String> detokenize(String); }
interface MaskContextFactory { MaskContext create(PiiDescriptor, MaskStrategy, String path, Class<?>); }

enum FailureMode { REDACT, THROW, PASS_THROUGH }
enum Sensitivity { ... atLeast(Sensitivity) }
```

`MaskingObserver` — every method `default`, so implement only what you need. `MaskingObserver.NOOP`
is the default.

| Method | Fires when |
|---|---|
| `onMasked(path, PiiCategory, MaskStrategy)` | a declared PII value was masked |
| `onUnannotatedPii(path, PiiCategory, detector)` | a detector hit a value **nobody declared** — the one to alert on |
| `onScanned(path, PiiCategory, detector)` | a detector hit inside a value declared `FREEFORM_TEXT`/`SCAN` — the design working |
| `onFailure(path, Throwable)` | masking failed and the `FailureMode` was applied |
| `onDepthLimitExceeded(path)` | the graph was deeper than `maxDepth` |
| `onCollectionTruncated(path, int kept)` | a collection or map was cut at `maxCollectionElements` |

The first pair and the last pair each used to be one method. Merging them is what made the alerting
signal unusable: an annotated free-text field produces detector hits on every request, and a
truncated collection reported a synthesised index that differed between the list and map cases.

### The static hand-off — `InstalledDataMask` / `ResolvedMasker<T>`

For a plugin the framework builds by class name, before the application has a container — a logback
appender, a log4j2 plugin, a Kafka interceptor. Do not hand-roll this again; three modules did, and
the caching rule is the part that is easy to get wrong.

```java
public final class DataMaskLogback {
    private static final InstalledDataMask INSTALLED = InstalledDataMask.holder();
    public static void install(DataMask d) { INSTALLED.install(d); }
    public static Optional<DataMask> installed() { return INSTALLED.installed(); }
    public static void uninstall() { INSTALLED.uninstall(); }
    static InstalledDataMask holder() { return INSTALLED; }
}

// in the plugin:
ResolvedMasker.of(masker)                                    // it was handed its own
ResolvedMasker.installed(holder, DataMask::engine-ish, this::ephemeralFallback)
```

The derived masker is keyed on the **identity of the installed instance**, which stays null while
nothing is installed — so the fallback is built once rather than per event, and a late install still
rewires. The `fallback` supplier is where the integration logs its warning, through its own
framework's internal status channel, never through the logger being masked; that is what keeps
core free of a logging dependency. The "one static per classloader" caveat is documented on
`InstalledDataMask` rather than in each integration.

### `datamask-api` — what a custom masker sees

```java
interface Masker { Object mask(Object value, MaskContext context);
                   default boolean supports(Class<?> type) { return true; } }
    // supports() receives the value's RUNTIME class, not the declared type. A member declared
    // Object says nothing about what a masker must handle, and answering "no" means redaction.

interface MaskContext { PiiCategory category(); Sensitivity sensitivity(); MaskStrategy strategy();
                        int keep(); char padding(); String replacement(); String path();
                        Class<?> declaredType(); String redactionPlaceholder();
                        String pseudonymize(String); String tokenize(String); }
```

`@PII` attributes: `strategy` (AUTO), `category` (UNSPECIFIED), `sensitivity` (HIGH), `masker`
(`Masker.class`), `keep` (-1 = category default), `padding` (`'*'`), `replacement` (`""`), `purpose`
(`""`). `@NoMask` requires a `justification`.

`MaskKey`: `ofSecret(String)` / `ofSecret(char[])` (reject under 16 bytes; prefer the `char[]` one —
a `String` cannot be wiped), `of(byte[])`, `ephemeral()`, `forPurpose(String)`, `spec()` (a fresh
one per call, so `destroy()` can promise something), `id()`, `algorithm()`, `destroy()`,
`isDestroyed()`, `isEphemeral()`.

`HmacPseudonymizer` writes `~<keyId>:<digest>`. Take a keyring — `new HmacPseudonymizer(current,
previous)` — and use `matches(value, pseudonym)` to confirm a surrogate issued before a rotation;
`DataMask.pseudonymMatches` is the facade for it. **Do not drop the key id from the format**: without
it, rotating a secret silently turns every pseudonym written beforehand into an unjoinable stranger,
with no error and nothing in a log to explain it.

`TokenVault`: the default is `RejectingTokenVault`, which **refuses**. `InMemoryTokenVault` is opt-in
via `Builder.vault(...)`, bounded by capacity and a 15-minute TTL. Never make the in-memory one the
default again — `TOKENIZE` appearing to work while raw PII accumulates in a heap map, with a
`detokenize` any caller can reach, is worse than it failing.

## Writing an integration module

The same decisions come up every time, and the answers are already settled.

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

**7. Register the module in `ModuleDependencyTest`, in this same change** — a row in `integrations()`
for a framework integration, a rule of its own for anything else. See *This layering is enforced*
above. Then run `./gradlew :datamask-architecture-tests:test` before you call the module done; that
task is the cheapest way to find out you coupled it to something you did not mean to.

## Deliberate non-goals

- No static "does this type carry PII" analysis. Declared types lie (`Object`, interfaces); the
  engine uses the runtime class and relies on the plan cache instead.
- No *further* build-time generation. `datamask-build-processor` already fills the `MaskPlanCompiler`
  port with generated plans (`ch.raph.datamask.processor.plan`, read back by
  `infrastructure/generated`), and `GeneratedMaskPlanCompiler` falls back to the reflective one for
  anything it did not cover — which is what keeps generation optional rather than a second engine.
  It steps aside entirely when `PolicyOverrides` is non-empty, because a plan resolved at compile
  time cannot know about an override and ignoring one would mean an unmasked value.
- No caching of value → pseudonym. It would speed up repeated ids but means holding PII in memory.
