---
name: datamask-build
description: DataMask's build, code formatting and versioning — Gradle multi-project layout, convention plugins, the version catalog, Spotless with palantir-java-format, Axion release versioning, and the testing conventions. Triggers on — running or fixing the build, adding a module or dependency, formatting complaints, spotlessCheck failures, version questions, writing tests, "how do I build this", CI setup.
---

# DataMask — build, formatting, versioning

## Layout

```
build.gradle                 root: axion versioning, misc Spotless formats, aggregated coverage + gate
settings.gradle              module includes; mavenCentral only
gradle.properties            sequential execution, build cache, daemon JVM args
gradle/libs.versions.toml    the single source of every version
buildSrc/
  build.gradle               pulls the Spotless plugin onto the build classpath
  settings.gradle            re-exposes ../gradle/libs.versions.toml as `libs`
  src/main/groovy/
    datamask.java-base-conventions.gradle  the Java baseline: toolchain, compiler args, JUnit
    datamask.java-conventions.gradle       java-base + publishing; applied by every published module
    datamask.spotless-conventions.gradle   Java formatting, applied via java-base-conventions
    datamask.coverage-conventions.gradle   JaCoCo per module, applied via java-base-conventions
    datamask.publishing-conventions.gradle Maven Central, via java-conventions and the BOM
datamask-*/build.gradle      one line of plugin, a description, dependencies
datamask-*/README.md         the module's own documentation (see below)
datamask-architecture-tests/ verification only, never published (see below)
```

The `java-base` / `java-conventions` split exists so `datamask-architecture-tests` can take the Java
baseline without becoming a published artifact. A module that ships applies
`datamask.java-conventions`; only a verification module applies the base directly.

Groovy DSL throughout. Convention plugins reach the catalog with
`extensions.getByType(VersionCatalogsExtension).named('libs')` — precompiled script plugins cannot
use the generated `libs` accessor directly.

## Documentation — one README per module

**The root `README.md` stays high level: what DataMask is, why it exists, basic usage, the module
table, requirements. It does not explain individual modules.** Every module documents itself in its
own `datamask-<name>/README.md`, and the root table links to each one.

So when a module is implemented, **writing its README is part of the work**, not a follow-up:

- Open with one bold sentence saying what the module protects, then the three-line usage snippet.
- Then the detail that has nowhere else to live: the leak it closes, what survives and what is
  removed, the decisions that would otherwise look arbitrary, and the deliberate non-goals.
- Anything in the root README that is specific to one module belongs in that module's README instead.

Core concepts follow the same rule rather than accumulating at the root: strategies, categories,
policy and the supported types live in `datamask-core/README.md`; the annotations, `@NoMask` and
custom `Masker`s live in `datamask-api/README.md`.

Also update, in the same change: the root README's module table (status and one-line description) and
the module map in the `datamask-overview` skill.

Markdown is formatted by Spotless too (`**/*.md` on the root project), so `./gradlew spotlessApply`
covers a README.

## Java baseline

`toolchain = 25` (build/test on the JDK the developer has) but `options.release = 21` — **21 is the
published bytecode level**. Also set: `-parameters` (needed so the reflective plan compiler can
recover constructor parameter names for non-record beans, and so Spring can bind
`@ConfigurationProperties` constructors) and `-Xlint:all,-serial,-processing`.

Every Java module gets `withSourcesJar()`, `withJavadocJar()` and a `maven-publish` publication.

## Versions

All in `gradle/libs.versions.toml`, **deliberately aligned to what Spring Boot 4.1.0 manages**, so
the starter is drop-in compatible with a Boot application's dependency management. Current pins:
Jackson 3.1.4, Logback 1.5.34, Log4j2 2.25.4, Kafka 4.2.1, OpenTelemetry 1.62.0, Micrometer 1.17.0,
PostgreSQL driver 42.7.11, JUnit 6.0.3, AssertJ 3.27.7, ArchUnit 1.4.1, JaCoCo 0.8.13.

JaCoCo is pinned rather than left to whatever the Gradle distribution bundles, so a wrapper bump
cannot move the numbers the coverage gate measures against. ArchUnit is the plain `archunit`
artifact, not `archunit-junit5`: that engine builds against the JUnit 5 platform and this build is on
JUnit 6, so the rules are driven from ordinary Jupiter tests instead.

When bumping Spring Boot, re-derive the rest from its `spring-boot-dependencies` POM rather than
picking latest independently.

Do not add Mockito. The tests do not need it and have stayed readable without it.

## Coverage — JaCoCo, aggregated, gated at 80%

Coverage is measured **across every module at once**, not module by module: a per-module threshold
would fail a module that is three annotations and pass one that is a thousand tested lines with an
untested integration hanging off it.

- `datamask.coverage-conventions.gradle` applies `jacoco` to each Java module and turns on the XML
  and HTML reports for its own `jacocoTestReport`, which is available on demand per module.
- The root project applies Gradle's `jacoco-report-aggregation`. Every module except the BOM and
  `datamask-architecture-tests` feeds the `jacocoAggregation` configuration, so the aggregate follows
  the project list rather than a hand-kept one.
- `coverageVerification` (root, `JacocoCoverageVerification`) enforces **80% INSTRUCTION coverage** on
  that same aggregate, reading the report task's own class dirs, sources and exec data so the number
  enforced is the number the report shows. INSTRUCTION is the counter least sensitive to formatting,
  so the threshold means the same thing after a reformat.
- Root `check` depends on it, which puts coverage in `./gradlew build` alongside formatting and tests.

```bash
./gradlew testCodeCoverageReport   # build/reports/jacoco/testCodeCoverageReport/html/index.html
./gradlew coverageVerification     # the gate on its own
```

The threshold lives in one place: `def coverageMinimum = 0.80` at the top of the root `build.gradle`.
At the time it was added the aggregate stood at 82.0% instruction / 80.9% line, so **the margin is
thin** — a new module landing with no tests will fail the gate, which is the intent.

In CI both workflows publish the aggregate through `.github/actions/coverage-report`: the counters go
into the run summary and the HTML report is uploaded as an artifact, with `if: ${{ !cancelled() }}` so
a run that fails the gate still explains itself.

## Architecture tests — `datamask-architecture-tests`

Not published, and it applies `datamask.java-base-conventions` precisely so it cannot be. It holds
every other module on its **test** classpath and states the dependency rules once for the whole
library, in `ModuleDependencyTest`:

- `datamask-api` depends on nothing but the JDK.
- `domain` sees only the annotations — never `application`, never `infrastructure`.
- `datamask-core` depends on no third-party library and on no integration module.
- each integration module depends on the core and on **its own** framework only — so no integration
  reaches another's, and none reaches into `infrastructure`.
- both processor modules depend on the JDK and the annotations only, never on the engine.
- `everyModuleIsCoveredByARule()` fails if a module has classes but no rule, so implementing one of
  the planned modules cannot silently opt out of the check.

**Implementing a module includes registering it here, in the same change** — a row in
`integrations()` with the framework packages it may use, or, for a module that is not a framework
integration, a rule of its own plus its name in `MODULES_WITH_THEIR_OWN_RULE`. Register it once the
module has its first class: a row for an empty package fails as a rule that matched no classes.
`datamask-architecture` has the detail on choosing between the two.

So the checklist for a module that has just gained code is: its `README.md`, the root README's module
table, the module map in `datamask-overview`, and `ModuleDependencyTest`. Then
`./gradlew :datamask-architecture-tests:test`.

`application -> infrastructure` is allowed on purpose and is the one exception to inward-only
dependencies: `DataMask.Builder` and `MaskerRegistry` are the composition root, and wiring the default
maskers, detectors, key and vault is their job.

## Execution — sequential, on purpose

`gradle.properties` sets `org.gradle.parallel=false`. **Spotless does not support parallel
execution.** With it on, roughly half of the `clean build` runs failed, either with a
`NoClassDefFoundError` thrown from inside a formatter or with Gradle unable to fingerprint spotless's
own `lineEndingsPolicy` input. Both are open upstream bugs with no fix (diffplug/spotless#2850,
diffplug/spotless#2391), and dropping a formatting step does not avoid them — removing `cleanthat()`
was measured and made no difference. Do not re-enable it; a build that is green half the time is worth
less than the few seconds it would save on fourteen small modules.

The build cache (`org.gradle.caching=true`) is on and is where the real speed comes from; in CI
`gradle/actions/setup-gradle` restores it.

## Formatting — Spotless + palantir-java-format

Java, in `datamask.spotless-conventions.gradle`, in this exact order:

```groovy
spotless {
    java {
        cleanthat()
        removeUnusedImports()
        formatAnnotations()
        palantirJavaFormat('2.97.0').style('PALANTIR')
        endWithNewline()
        toggleOffOn()
    }
}
```

Non-Java formats are declared **only on the root project**, targeting `**/*.gradle`, `**/*.md` and
`**/.gitignore`, excluding `build/`, `.gradle/` and `.idea/`. Declaring them per project would make
every subproject's glob reach back up and format the same files several times over.

Palantir puts all imports in one block, `java.*` inline and alphabetically sorted — do not hand-sort
imports into groups, Spotless will undo it. Wrapping is 120 columns.

```bash
./gradlew spotlessApply     # fix
./gradlew spotlessCheck     # verify; runs as part of `check`, so `build` enforces it
```

`toggleOffOn()` is enabled: `// spotless:off` … `// spotless:on` around anything hand-aligned.

## Versioning — Axion

`pl.allegro.tech.build.axion-release`, configured on the root project only, with the version pushed
into `allprojects`.

- `versionCreator 'versionWithBranch'`, `versionIncrementer 'incrementMinor'`, initial version
  `0.1.0`.
- Snapshots off `main` are `-rc-<shortRevision>`; off any other branch they are `-alpha` plus the
  short revision unless `datamask.snapshotVersionWithCommitHash=false` is set in
  `gradle.properties`.
- `repository.pushTagsOnly = true`.
- All three `checks` are off (`uncommittedChanges`, `aheadOfRemote`, `snapshotDependencies`): the
  version must be derivable in CI from a shallow, detached checkout with local changes. Release
  gating belongs in the pipeline, not in versioning.

Two Groovy-specific gotchas already solved in `build.gradle`, do not "clean them up":

- `tag.initialVersion` holds a **SAM type**, not a `Closure`. It needs an explicit
  `as ...TagProperties.InitialVersionSupplier` coercion.
- A repository with **no commits** has no HEAD, so axion throws `ScmException` caused by
  `NoHeadException`. The root build catches exactly that cause and falls back to `0.1.0-SNAPSHOT`,
  so the build still configures before the very first commit. Any other `ScmException` is rethrown.

```bash
./gradlew currentVersion
./gradlew release
```

## Commands

```bash
./gradlew build                       # compile + spotlessCheck + test + architecture rules
                                      #   + 80% coverage gate + jars
./gradlew :datamask-core:test         # one module
./gradlew spotlessApply               # before committing
./gradlew clean build                 # from scratch
./gradlew testCodeCoverageReport      # aggregated coverage, HTML + XML
./gradlew coverageVerification        # the 80% gate on its own
./gradlew :datamask-architecture-tests:test   # the module dependency rules on their own
```

## Testing conventions

JUnit 6 (Jupiter) + AssertJ. `@DisplayName` on classes and methods, written as a sentence describing
the **behaviour and why it matters**, not the method name — `"redacts the CVV entirely, overriding
an annotation that asked to keep three"`. `@Nested` classes group by scenario.
`@ParameterizedTest` + `@ValueSource` for check-digit tables.

Shared fixtures live in `ch.raph.datamask.testdomain.Banking` — records and beans shaped like a real
banking domain (`Customer`, `Card`, `Account`, `Profile`, `Portfolio`, plus a Lombok-style bean, a
no-arg bean and a self-referencing node).

Assert on **absence of the raw value**, not only on the expected mask:

```java
assertThat(masked.iban()).doesNotContain("9300762011623852957");
```

An equality assertion passes just as well when the mask is wrong in a safe direction; a
`doesNotContain` is what actually catches a leak.

Current state: 63 tests in `datamask-core`, all green.

## Known expected outputs, for reference

A 21-character Swiss IBAN masks to `CH93 **** **** **** *295 7`. The trailing single character is
**correct** ISO 13616 grouping — groups of four from the left. Do not "fix" it.
