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
// Customer[email=Email[value=j*******@e******.com], iban=~a3Kd9Q:7fPqR2xLmA0Ztb1Xw, country=CH]
```

The target industry is **banking and fintech**, with Swiss specifics (AVS/AHV numbers, Swiss IBANs)
treated as first-class. Assume the reader of a masked log is a support agent or an SRE, and the
auditor of the code is a compliance officer.

## Coordinates and baseline

| | |
|---|---|
| Group | `ch.raph.datamask` |
| Root package | `ch.raph.datamask` |
| Published Java baseline | **25** (`options.release = 25`) |
| Build/test JDK | **25** (toolchain) |
| Gradle | 9.7, Groovy DSL |
| Spring Boot target | **4.1.0** (implies Jackson 3, `tools.jackson.*`) |
| Licence | Apache 2.0 |

The baseline was raised from 21 to 25 (current LTS) in August 2026: the library uses Java 25
language and API features freely — `javax.crypto.KDF` (HKDF), record patterns, unnamed variables.

## Module map

| Module | Status | Purpose |
|---|---|---|
| `datamask-api` | **implemented** | Annotations + SPI. **Zero dependencies, and it must stay that way** |
| `datamask-core` | **implemented** | Engine: plan compilation, strategies, detectors, policy, crypto |
| `datamask-bom` | **implemented** | Platform pinning every module |
| `datamask-jackson` | **implemented** | Jackson **3**; masks at serialization time |
| `datamask-logback` | **implemented** | Masking appender: log arguments, message bodies, MDC, exception messages |
| `datamask-log4j2` | **implemented** | Rewrite policy + pattern converter |
| `datamask-opentelemetry` | scaffolded, empty | Span attributes, events, log records before export |
| `datamask-kafka` | **implemented** | Masking serializer, Streams serde, producer and consumer interceptors, headers included |
| `datamask-jdbc` | **implemented** | PostgreSQL error details **and** bind parameters |
| `datamask-jpa` | scaffolded, empty | `AttributeConverter`s for pseudonymised columns at rest |
| `datamask-ai` | scaffolded, empty | Prompt sanitisation with reversible placeholders |
| `datamask-spring-boot-autoconfigure` | **implemented** | One `DataMask` from `datamask.*`, wired into every module on the classpath |
| `datamask-spring-boot-starter` | **implemented** | Core plus the auto-configuration; integrations stay opt-in |
| `datamask-check-processor` | **implemented** | Compile-time validation of `@PII` usage |
| `datamask-build-processor` | **implemented** | Mask plans generated at compile time; `GeneratedMaskPlanCompiler` in core reads them |
| `datamask-benchmarks` | **implemented** | JMH: what masking costs per log event and per object graph. Never published |
| `datamask-architecture-tests` | **verification only** | ArchUnit rules over the dependencies between the modules. Never published |

"Scaffolded, empty" means `build.gradle` with correct dependencies exists and builds; there is no
`src/` yet.

`datamask-architecture-tests` is not an artifact: it applies `datamask.java-base-conventions` rather
than `datamask.java-conventions` so it has no route to Central, and both the BOM and the coverage
aggregation exclude it by name. `datamask-benchmarks` is unpublished by exactly the same mechanism —
copy that pattern for anything else that verifies or measures the library rather than being part of
it.

**Implementing a module is not finished until `ModuleDependencyTest` knows about it** — the module map
above and that test are the two places a new module has to be registered, and
`everyModuleIsCoveredByARule()` fails the build until it is. See `datamask-architecture`.

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

Done: **`datamask-jackson`** (masks at serialization time), **`datamask-jdbc`** (PostgreSQL error
details plus bind parameters), **`datamask-logback`** + **`datamask-log4j2`**, **`datamask-kafka`**
(masking serializer, producer interceptor, headers), **`datamask-spring-boot-autoconfigure`** +
**starter**, and both processors — **`datamask-check-processor`** (validates `@PII` usage) and
**`datamask-build-processor`** (generates the mask plans). What remains:

1. **`datamask-opentelemetry`**.
2. **`datamask-jpa`** — `AttributeConverter`s for pseudonymised columns at rest. Pairs with
   `datamask-jdbc`, which protects what the database *says*; this protects what it *stores*.
3. **`datamask-ai`** — sanitise the prompt, keep a reversible map, re-identify the model's answer
   locally.

**A new integration module is not finished until the Spring auto-configuration knows about it**, the
same way it is not finished until `ModuleDependencyTest` does. That means a
`DataMask<Name>AutoConfiguration` in `ch.raph.datamask.spring`, a line in the module's
`AutoConfiguration.imports`, a `compileOnly` and a `testImplementation` on it in the
autoconfigure module's `build.gradle`, and a `datamask.<name>.enabled` component in
`DataMaskProperties`. The two shapes already exist to copy: a bean the framework collects
(`DataMaskJacksonAutoConfiguration`), or an installer bean filling in a static hand-off for a plugin
the framework builds by class name (`LogbackDataMaskInstaller`, `KafkaDataMaskInstaller`).

The benchmark module was deferred until an integration put the engine on a logging hot path; logback
and log4j2 did, so **`datamask-benchmarks` now exists** and the deferral is over. Its headline is a
clean `INFO` line through the masking appender against the same event through a plain one, and its
first result is worth knowing before touching anything on that path: a clean line cost ~11 µs, and
~98% of that was the unfiltered regex fan-out in `TextSanitizer`, not the engine. **Gating the
detectors took that to ~0.55 µs** (~3.4 µs on a log line with digits and colons in it, which is the
figure to quote for real text). See `datamask-benchmarks/README.md`; a performance change to the core
or to a logging module should come with a before/after from it, and `docs/IMPROVEMENTS.md` holds the
baseline table both columns live in.

Two things the implemented integrations established that the remaining ones should copy: an
integration takes a `MaskingEngine` (with a `DataMask` convenience constructor), and it returns the
**original object unchanged** when there was nothing to mask. The API surface reference and the
five-step recipe are in `datamask-architecture`, so writing a new integration should not need a
reading pass over `datamask-core`.

## Related skills

- `datamask-architecture` — package layering, key types, how the engine actually works
- `datamask-invariants` — the security rules that must never be broken
- `datamask-build` — Gradle, Spotless/Palantir formatting, Axion versioning, commands
