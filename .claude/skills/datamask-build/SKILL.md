---
name: datamask-build
description: DataMask's build, code formatting and versioning — Gradle multi-project layout, convention plugins, the version catalog, Spotless with palantir-java-format, Axion release versioning, and the testing conventions. Triggers on — running or fixing the build, adding a module or dependency, formatting complaints, spotlessCheck failures, version questions, writing tests, "how do I build this", CI setup.
---

# DataMask — build, formatting, versioning

## Layout

```
build.gradle                 root: axion versioning, misc Spotless formats
settings.gradle              module includes; mavenCentral only
gradle/libs.versions.toml    the single source of every version
buildSrc/
  build.gradle               pulls the Spotless plugin onto the build classpath
  settings.gradle            re-exposes ../gradle/libs.versions.toml as `libs`
  src/main/groovy/
    datamask.java-conventions.gradle      applied by every Java module
    datamask.spotless-conventions.gradle  Java formatting, applied via java-conventions
datamask-*/build.gradle      one line of plugin, a description, dependencies
datamask-*/README.md         the module's own documentation (see below)
```

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
PostgreSQL driver 42.7.11, JUnit 6.0.3, AssertJ 3.27.7.

When bumping Spring Boot, re-derive the rest from its `spring-boot-dependencies` POM rather than
picking latest independently.

Do not add Mockito. The tests do not need it and have stayed readable without it.

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
./gradlew build                       # compile + spotlessCheck + test + jars
./gradlew :datamask-core:test         # one module
./gradlew spotlessApply               # before committing
./gradlew clean build                 # from scratch
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
