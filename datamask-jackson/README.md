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

**Everything else** goes through the detectors, via a serializer registered for `String` rather than
per property. That covers strings inside lists, map values and the root of the document — places no
property-level hook can reach — so an IBAN that ended up in a free-text field is still caught. Each
hit is reported to `MaskingObserver.onUnannotatedPii`, the earliest warning that a field has started
carrying PII nobody classified.

The second half follows `MaskingPolicy#scanUnannotatedText` and disappears entirely when it is off.
Properties the plan already decided on never reach it: they are given their own serializer.

## Three decisions worth knowing about

**A property declaring both `@PII` and `@JsonSerialize(using = ...)` is masked.** Jackson's
`assignSerializer` refuses to replace a serializer a property already has, so the property writer is
copied and the masking serializer set directly. A custom renderer must not be a way around the
annotation.

**`@NoMask` keeps a value out of the scanner too**, not only out of its declared masking. An exemption
that a detector could still rewrite is not an exemption.

**Deserialization is untouched, on purpose.** This module protects what *leaves* the process. Masking
on the way in would silently destroy the data the application is meant to store.

## Polymorphism

A property carrying `@JsonTypeInfo` is masked before the type serializer runs, and the **masked** value
decides which serializer is used — a strategy is free to return something of a different type than it
was given, and `NULLIFY` returns nothing at all.

## Jackson 3

This module targets Jackson 3 (`tools.jackson.*`): `JacksonModule`, `ValueSerializer`,
`SerializationContext`, `ValueSerializerModifier`. Annotations remain `com.fasterxml.jackson.annotation.*`.

Constructing from a `MaskingEngine` instead of a `DataMask` is also supported, for a Spring
auto-configuration that already holds one.

## Tests

24 tests, asserting the raw value is **absent from the document** rather than only that the masked form
is present, and covering the fail-closed paths: a broken masker yields the placeholder under `REDACT`
and aborts the document under `THROW`, with the value in neither exception message.
