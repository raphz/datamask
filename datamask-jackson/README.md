# datamask-jackson

**Masks PII while the JSON document is written, so the raw value is never written and never has to be
scrubbed afterwards.**

```java
ObjectMapper mapper = JsonMapper.builder()
        .addModule(new DataMaskModule(dataMask))
        .build();
```

Nothing else changes. The domain model keeps its `@PII` annotations, no call site is touched, and no
masked copy of the object graph is built.

Serialization is the narrowest point every outbound value passes through, which makes it the cheapest
place to enforce masking — and it means a field added to a DTO next year is covered the day it is
added.

## Two hooks, and the split between them is the point

**Declared PII** is handled from the compiled `MaskPlan`, where `@PII`, `@NoMask` and any policy
override have already collapsed into one decision per property. That decision is taken **once**, while
Jackson builds the serializer for the type, so the reflective plan lookup is off the serialization path
entirely; afterwards masking a property costs one virtual call.

**Everything else** goes through the detectors, via serializers registered by *type* rather than per
property. That covers strings inside lists, map values, map keys, `JsonNode` trees, non-`String`
`CharSequence`s and the root of the document — places no property-level hook can reach — so an IBAN
that ended up in a free-text field is still caught. Each hit is reported to
`MaskingObserver.onUnannotatedPii`, the earliest warning that a field has started carrying PII nobody
classified — never `onScanned`, which belongs to text somebody declared as free text. See
[Which observer signal, and why](#which-observer-signal-and-why).

The second half follows `MaskingPolicy#scanUnannotatedText` and disappears entirely when it is off.
Properties the plan already decided on never reach it: they are given their own serializer.

## What the scanner reaches, and what it does not

Jackson writes different things through different lookups, and each one had to be hooked separately.

| Written value | How it is covered |
|---|---|
| A `String` property, list element, map value, root value | `String` serializer |
| A `StringBuilder` or any other `CharSequence` | `CharSequence` serializer — Jackson writes those by another route |
| A **map key** | key serializer — a wholly separate lookup, so a `Map<String, Balance>` keyed by IBAN used to write every account number untouched |
| A `JsonNode` tree, at any depth | node serializer, which masks a copy of the tree and lets that write itself |
| A string in a slot carrying `@JsonTypeInfo` | the same serializers, which write the type id themselves |

A key is masked when **either** `maskMapKeys` or `scanUnannotatedText` is on. The engine keeps key
masking opt-in because masking a key changes what an entry can be looked up by; here nothing is
looked up, the application's own map is untouched, and a key is very often the identifier the whole
library exists to hide. Names inside a `JsonNode` object follow the same rule, for the same reason.
Bean property names never do: those are code, not data.

Out of scope, deliberately:

- **Values that are not text.** A detector reads characters. An account number stored in a `long` is
  masked only if it is annotated.
- **Keys that are not a `CharSequence`** — an enum, a UUID, a value object rendered through
  `@JsonKey`. They are written by their own key serializer and are not scanned.
- **A property that brings its own serializer and no `@PII`.** Nothing there declares the value is
  PII, and the serializer writes to the generator directly. Add `@PII` and masking outranks it.
- **`@JsonRawValue` and anything else written raw**, which bypasses every serializer.
- **Deserialization**, see below.

## The paths an observer sees

Every path this module reports follows the shared grammar, `<module>:<site>[/<detail>]` — documented
once in the core README, and the reason a SIEM rule keying on the prefix can tell a Jackson finding
from a JDBC or a Kafka one. Before this, a masked property was reported as a bare `Customer.iban`,
indistinguishable from what `DataMask.mask()` produces when an application masks the same object
itself.

| Site | Path | What it was |
|---|---|---|
| The bean's own name | `jackson:Customer/iban` | A property the compiled `MaskPlan` decided on |
| `text` | `jackson:text/reference` | A string nobody declared, in the named property |
| `text` | `jackson:text` | The same, written outside any property — the root of the document, or an array element there |
| `key` | `jackson:key/attributes` | A map key, whose enclosing property is one context out |
| `tree` | `jackson:tree/payload` | A string anywhere inside a `JsonNode` |
| `tree` | `jackson:tree/payload{key}` | A property *name* inside that tree; `{key}` is the engine's own convention |

The detail is the enclosing **property** name, never a JSON pointer and never a map key. A pointer
would allocate on every string in every document; a key is very often the identifier this library
exists to hide, and a path reaches observers and exception messages.

## Which observer signal, and why

`onUnannotatedPii` is the alert-worthy one — a field nobody classified has started carrying PII —
and it stays that way only if nothing else is reported through it.

- **Declared free text** (`FREEFORM_TEXT`, or `@PII(strategy = SCAN)`) is reported to
  **`onScanned`**. Those findings are the scanner doing the job it was asked to do. Such a property
  never reaches this module's scanning serializers at all: the modifier gives it its own serializer
  and the engine routes it to `TextSanitizer.sanitizeDeclared`.
- **Everything the detectors reach here** — an unannotated string, a map key, a `JsonNode` — is
  reported to **`onUnannotatedPii`**.

A finding inside a `JsonNode` belongs in the second group, which is worth spelling out because a
tree looks like free text. It is not *declared* free text: a `JsonNode` member says the **shape** is
unknown, never anything about the content, and a property that did declare its content never
arrives there. So a hit in a tree is precisely the actionable case — an upstream payload has started
carrying PII and the contract needs a policy — and it hangs off the `scanUnannotatedText` switch
accordingly.

`onCollectionTruncated` is never fired by this module: Jackson writes collections itself, and the
engine only ever sees one declared value at a time here.

## Dropping a property outright

`MaskAction.Drop` is the only form of masking that leaves no trace of the field in the document —
not even a `null` saying it exists. A deployment asks for it through `PolicyOverrides`:

```java
DataMask.builder()
        .secret(secret)
        .overrides(PolicyOverrides.builder().drop(Party.class, "reference").build())
        .build();
```

The drop is decided **before** `@NoMask`: an exemption is the code author's claim that a member is
harmless, an override is the deployment disagreeing, and the deployment is the one being audited.
A dropped property stays absent through `@JsonUnwrapped` too, prefix and all.

## Three decisions worth knowing about

**A property declaring both `@PII` and `@JsonSerialize(using = ...)` is masked.** Jackson's
`assignSerializer` refuses to replace a serializer a property already has, so the property writer is
copied and the masking serializer set directly. A custom renderer must not be a way around the
annotation.

**`@NoMask` keeps a value out of the scanner too**, not only out of its declared masking. An exemption
that a detector could still rewrite is not an exemption.

**Deserialization is untouched, on purpose.** This module protects what *leaves* the process. Masking
on the way in would silently destroy the data the application is meant to store.

## Polymorphism and `@JsonUnwrapped`

A property carrying `@JsonTypeInfo` is masked before the type serializer runs, and the **masked** value
decides which serializer is used — a strategy is free to return something of a different type than it
was given, and `NULLIFY` returns nothing at all. The scanner writes type ids too: a string that lands
in a polymorphic slot is masked and then written with its type prefix, rather than aborting the
document the way the base `ValueSerializer` would.

`@JsonUnwrapped` flattens a nested object into its holder, and Jackson does that by rebuilding every
property writer of the nested type — under a new name when the annotation carries a prefix or a
suffix. The masking writer is rebuilt with it, so a masked property stays masked, a `@NoMask` one
stays exempt and out of the scanner, and a dropped one stays absent. A failure to fail closed here
would be silent, which is why each of those four is a test.

## Failing closed at the boundary

A masker is application code and it can throw. A declared property follows `FailureMode`, which is
what aborts the document under `THROW`. A string the **scanner** reached is different: nobody
declared it, and turning an unannotated free-text field into an HTTP 500 is not a bargain anyone
asked for. The string is withheld — the whole value becomes the redaction placeholder, never the text
that failed to mask — and `MaskingObserver.onFailure` is told, which is the same trade the logging
integrations make.

## Jackson 3

This module targets Jackson 3 (`tools.jackson.*`): `JacksonModule`, `ValueSerializer`,
`SerializationContext`, `ValueSerializerModifier`. Annotations remain `com.fasterxml.jackson.annotation.*`.

Constructing from a `MaskingEngine` instead of a `DataMask` is also supported, for a Spring
auto-configuration that already holds one.

## Tests

48 tests, asserting the raw value is **absent from the document** rather than only that the masked form
is present, and covering the fail-closed paths: a broken masker yields the placeholder under `REDACT`,
aborts the document under `THROW` when the property was declared, and withholds the string when the
scanner reached it — with the value in no exception message and in no observer path.

The path grammar has a test per site, plus one that sweeps every signal and asserts nothing is
reported without the `jackson:` scheme.
