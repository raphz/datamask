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
| `datamask-jackson` | **implemented** | Jackson **3**; masks at serialization time |
| `datamask-logback` | scaffolded, empty | Log arguments, message bodies, MDC, exception messages |
| `datamask-log4j2` | scaffolded, empty | Rewrite policy + pattern converter |
| `datamask-opentelemetry` | scaffolded, empty | Span attributes, events, log records before export |
| `datamask-kafka` | scaffolded, empty | Masking serializer + producer interceptor, headers included |
| `datamask-jdbc` | **implemented** | PostgreSQL error details **and** bind parameters |
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

## Documentation layout

**The root `README.md` is high level only** — what DataMask is, why, basic usage, the module table
with links, requirements. Each module explains itself in `datamask-<name>/README.md`, and writing it
is part of implementing the module. Core concepts live in `datamask-core/README.md`, annotations and
the SPI in `datamask-api/README.md`. See `datamask-build` for what a module README contains.

## Roadmap — next modules, in the intended order

Done: **`datamask-jackson`** (masks at serialization time) and **`datamask-jdbc`** (PostgreSQL error
details plus bind parameters). What remains:

1. **`datamask-logback`** + **`datamask-log4j2`**.
2. **`datamask-spring-boot-autoconfigure`** + **starter**. Registration file is
   `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
   **Key-policy decision already made: fail fast.** `HASH`/`TOKENIZE` must refuse to start without
   a configured secret; dev/test may opt into an ephemeral key explicitly. Never ship a built-in
   default key. It should auto-configure `DataMaskModule` and wrap any `DataSource` bean in a
   `MaskingDataSource`.
3. **`datamask-opentelemetry`**, **`datamask-kafka`**.
4. **`datamask-jpa`** — `AttributeConverter`s for pseudonymised columns at rest. Pairs with
   `datamask-jdbc`, which protects what the database *says*; this protects what it *stores*.
5. **`datamask-ai`** — sanitise the prompt, keep a reversible map, re-identify the model's answer
   locally.
6. **`datamask-processor`**.

A benchmark module (JMH) was considered and deliberately deferred — worth adding once an
integration puts the engine on a logging hot path.

Two things the implemented integrations established that the remaining ones should copy: an
integration takes a `MaskingEngine` (with a `DataMask` convenience constructor), and it returns the
**original object unchanged** when there was nothing to mask. The API surface reference and the
five-step recipe are in `datamask-architecture`, so writing a new integration should not need a
reading pass over `datamask-core`.

## Related skills

- `datamask-architecture` — package layering, key types, how the engine actually works
- `datamask-invariants` — the security rules that must never be broken
- `datamask-build` — Gradle, Spotless/Palantir formatting, Axion versioning, commands
