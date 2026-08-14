---
name: datamask-overview
description: START HERE for any work on the DataMask library. Explains what DataMask is, the module map, what is implemented versus planned, and the roadmap. Triggers on — any task in the datamask repository, "what is this project", "what should I build next", adding or changing a module, onboarding to the codebase, resuming work in a new session.
---

# DataMask — what this library is

**Value proposition: "PII never accidentally reaches logs, traces or AI prompts."**

An annotation-driven masking library for Java. A developer declares what a value *is*, once, on the
domain model. DataMask makes sure it cannot leak anywhere else.

```java
public record Customer(
        @PII Email email,
        @PII(strategy = HASH) String iban,
        String country) { }

Customer safe = dataMask.mask(customer);
// Customer[email=Email[value=j*******@e******.com], iban=~7Kd9fPqR2xLmA0Zt, country=CH]
```

The target industry is **banking and fintech**, with Swiss specifics (AVS/AHV numbers, Swiss IBANs)
treated as first-class. Assume the reader of a masked log is a support agent or an SRE, and the
auditor of the code is a compliance officer.

## Coordinates and baseline

| | |
|---|---|
| Group | `ch.raph.datamask` |
| Root package | `ch.raph.datamask` |
| Published Java baseline | **21** (`options.release = 21`) |
| Build/test JDK | **25** (toolchain) |
| Gradle | 9.7, Groovy DSL |
| Spring Boot target | **4.1.0** (implies Jackson 3, `tools.jackson.*`) |
| Licence | Apache 2.0 |

Java 21 rather than 25 is deliberate: this is a library banks embed everywhere, and most banking
production estates run 21. Build on 25, publish for 21.

## Module map

| Module | Status | Purpose |
|---|---|---|
| `datamask-api` | **implemented** | Annotations + SPI. **Zero dependencies, and it must stay that way** |
| `datamask-core` | **implemented** | Engine: plan compilation, strategies, detectors, policy, crypto |
| `datamask-bom` | **implemented** | Platform pinning every module |
| `datamask-jackson` | scaffolded, empty | Jackson **3** module; mask at serialization time |
| `datamask-logback` | scaffolded, empty | Log arguments, message bodies, MDC, exception messages |
| `datamask-log4j2` | scaffolded, empty | Rewrite policy + pattern converter |
| `datamask-opentelemetry` | scaffolded, empty | Span attributes, events, log records before export |
| `datamask-kafka` | scaffolded, empty | Masking serializer + producer interceptor, headers included |
| `datamask-jdbc` | scaffolded, empty | Bind parameters **and PostgreSQL error details** |
| `datamask-jpa` | scaffolded, empty | `AttributeConverter`s for pseudonymised columns at rest |
| `datamask-ai` | scaffolded, empty | Prompt sanitisation with reversible placeholders |
| `datamask-spring-boot-autoconfigure` | scaffolded, empty | Auto-configuration for everything on the classpath |
| `datamask-spring-boot-starter` | scaffolded, empty | The single dependency an application adds |
| `datamask-processor` | scaffolded, empty | Compile-time validation of `@PII` usage |

"Scaffolded, empty" means `build.gradle` with correct dependencies exists and builds; there is no
`src/` yet.

## Why `datamask-api` has no dependencies

A domain module can depend on it to declare `@PII` on its records without taking on the masking
engine, a reflection library, or a logging framework. **Adding a dependency to `datamask-api` is a
breaking design change** — raise it explicitly rather than doing it.

Custom `Masker` implementations therefore also compile against `datamask-api` alone; the
`MaskContext` handed to them exposes `pseudonymize` and `tokenize` precisely so they never need
`datamask-core`.

## Roadmap — next modules, in the intended order

1. **`datamask-jackson`** — Jackson 3 API surface is already verified: `JacksonModule` (was
   `Module`), `ValueSerializer` (was `JsonSerializer`), `SerializationContext` (was
   `SerializerProvider`), `ValueSerializerModifier` (was `BeanSerializerModifier`), all under
   `tools.jackson.databind.*`. Annotations stay `com.fasterxml.jackson.annotation.*`. Masking here
   happens at serialization time, so the raw string is never materialised.
2. **`datamask-logback`** + **`datamask-log4j2`**.
3. **`datamask-spring-boot-autoconfigure`** + **starter**. Registration file is
   `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
   **Key-policy decision already made: fail fast.** `HASH`/`TOKENIZE` must refuse to start without
   a configured secret; dev/test may opt into an ephemeral key explicitly. Never ship a built-in
   default key.
4. **`datamask-opentelemetry`**, **`datamask-kafka`**.
5. **`datamask-jdbc`** — the high-value feature is sanitising PostgreSQL error details. A unique
   constraint violation echoes row values verbatim: `Detail: Key (email)=(john@x.com) already
   exists.` That is a real, common leak.
6. **`datamask-ai`** — sanitise the prompt, keep a reversible map, re-identify the model's answer
   locally.
7. **`datamask-processor`**.

A benchmark module (JMH) was considered and deliberately deferred — worth adding once an
integration puts the engine on a logging hot path.

## Related skills

- `datamask-architecture` — package layering, key types, how the engine actually works
- `datamask-invariants` — the security rules that must never be broken
- `datamask-build` — Gradle, Spotless/Palantir formatting, Axion versioning, commands
