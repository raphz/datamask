# datamask-architecture-tests

**Holds every DataMask module on its test classpath and fails the build when the dependencies between
them drift.** It is verification, not a library: it is never published, and nothing depends on it.

```bash
./gradlew :datamask-architecture-tests:test
```

## What it enforces

`ModuleDependencyTest` states the rules once for the whole library, using
[ArchUnit](https://www.archunit.org) over the compiled bytecode:

| Rule | Why it is worth a build failure |
|---|---|
| `datamask-api` depends on nothing but the JDK | A domain module declares `@PII` without taking on the masking engine, a reflection library or a logging framework. One import is all it takes to lose that. |
| `domain` sees the annotations only, never `application` or `infrastructure` | Keeps the masking vocabulary independent of how masking is carried out. |
| `datamask-core` depends on no third-party library and on no integration module | A security library with a framework on its critical path inherits that framework's version constraints and its CVEs. |
| An integration depends on the core and on **its own** framework | An application that wants `datamask-jdbc` should never pull in Jackson or Log4j2. |
| An integration never reaches into `infrastructure` | An integration is handed a `MaskingEngine` and speaks `api`, `domain` and `application` types; touching an adapter couples it to a masking implementation it has no business knowing about. |
| Every module with classes is covered by one of the above | Otherwise implementing a planned module silently opts it out of the check. |

`application -> infrastructure` is deliberately allowed, and is the only exception to inward-only
dependencies: `DataMask.Builder` and `MaskerRegistry` are the composition root, and wiring the default
maskers, detectors, key and vault is exactly their job.

## Adding a module

When a module gets its first class, add a row to `ModuleDependencyTest.integrations()` naming the
framework packages it may reach for:

```java
new Integration("kafka", List.of("org.apache.kafka.."))
```

The row goes in when the code does, not before: a rule whose package contains no classes fails as a
rule that matched nothing. Forgetting the row is caught too — `everyModuleIsCoveredByARule()` compares
the packages present in the bytecode against the rules that exist.

## Why a separate module

The rules are about dependencies *between* modules, so they need every module's bytecode at once, and
no published module can depend on all the others. This one applies
`datamask.java-base-conventions` rather than `datamask.java-conventions`, which is the same Java
baseline without a route to Maven Central — so it cannot be published by accident. The BOM and the
coverage aggregation both exclude it: it pins no coordinate and contributes no classes to cover.

ArchUnit is used through the plain `archunit` artifact with ordinary Jupiter tests, not the
`archunit-junit5` engine, which builds against the JUnit 5 platform while this build is on JUnit 6.
