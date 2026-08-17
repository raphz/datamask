# datamask-spring-boot-autoconfigure

**Builds one `DataMask` from configuration and wires it into every DataMask module on the
classpath.**

```yaml
datamask:
  secret: ${DATAMASK_SECRET}
```

That is the whole setup. The `DataMask` bean exists, Jackson masks what it serialises, the JDBC pool
is wrapped, and the logging and Kafka plugins find the instance they could not be handed.

Most applications add [`datamask-spring-boot-starter`](../datamask-spring-boot-starter/README.md)
rather than this module directly.

## Why it can refuse to start

`HASH` and `TOKENIZE` are keyed. Without `datamask.secret` there are only two things this module
could do, and neither is defensible as a default:

- **A key of its own.** It would be inside the published artifact, so everybody would have it, and
  every pseudonym in production would be reversible by anyone who downloaded the jar. Under GDPR
  Article 4(5) the output would not be pseudonymised data at all.
- **A random key per JVM.** Safe, but pseudonyms stop matching across instances and across restarts,
  which removes the only reason to prefer `HASH` over `REDACT` — and nothing about the running system
  would look wrong.

So the context fails instead, with a message naming the property to set. Development and test can opt
into the random key by saying so:

```yaml
datamask:
  ephemeral-key: true    # local and test only; everything is masked, nothing is comparable
```

Setting both is a profile that inherited both. The secret wins and the disagreement is logged: between
two configured answers, the stronger one is the safe one.

A secret that is *present but shorter than 16 bytes* refuses to start for the same reason, and gets a
startup message of its own rather than an `IllegalArgumentException` out of the crypto adapter. It is
at least as common as the missing one — a team setting the property for the first time reaches for a
value they can type — and stretching a short secret into an acceptable one would be the same mistake
as shipping a default key. Neither that message nor any log line repeats the configured value, its
length, or any part of it.

## The secret is kept out of Actuator

`/actuator/env` and `/actuator/configprops` hide values by default. They do not under
`management.endpoint.env.show-values: always`, which internal deployments set routinely — and at that
point the only thing between a property and the response body is a `SanitizingFunction` that claims
the key. This module contributes one, when the Actuator is on the classpath:

```yaml
management:
  endpoint:
    env:
      show-values: always     # datamask.secret is still ******
```

The match is a shape rather than a property name. Relaxed binding means the same secret is
`datamask.secret` in `configprops` and whatever spelling the property source used in `env` —
`DATAMASK_SECRET`, `datamask-secret` — and it covers key material this library has not shipped yet,
because a rule listing one name fails silently the day a second one exists. `datamask.ephemeral-key`
is the one exclusion: it ends in `key` but holds a boolean, and whether a deployment is running under
a random per-JVM key is exactly what an operator opens `/env` to find out.

The function is registered whether or not `datamask.enabled` is set, because turning masking off
removes the `DataMask` bean and not the secret sitting in the environment. Applications without the
Actuator get nothing and notice nothing.

## Properties

| Property | Default | |
|---|---|---|
| `datamask.enabled` | `true` | Off means no `DataMask` bean and no wiring — and no failure over the secret it no longer needs. |
| `datamask.secret` | — | At least 16 bytes. Read it from a secret manager, not from `application.yml`. |
| `datamask.ephemeral-key` | `false` | Explicit opt-in to a random per-JVM key. |
| `datamask.policy.preset` | `STRICT` | `STRICT` or `RELAXED`, as the base every property below adjusts. |
| `datamask.policy.threshold` | preset | Values at or above this sensitivity are masked. |
| `datamask.policy.failure-mode` | preset | `REDACT`, `THROW` or `PASS_THROUGH`. |
| `datamask.policy.redaction-placeholder` | preset | What a redacted value becomes. |
| `datamask.policy.max-depth` | preset | Traversal bound. |
| `datamask.policy.max-collection-elements` | preset | Collection bound; the tail is dropped. |
| `datamask.policy.scan-unannotated-text` | preset | Whether free text is scanned for PII nobody declared. |
| `datamask.policy.mask-map-keys` | preset | Masking a key changes lookup semantics, so this is separate. |
| `datamask.jackson.enabled` | `true` | |
| `datamask.logback.enabled` | `true` | |
| `datamask.log4j2.enabled` | `true` | |
| `datamask.jdbc.enabled` | `true` | |
| `datamask.jdbc.excluded-beans` | — | Names of `DataSource` beans to leave unwrapped. Each one is logged at startup. |
| `datamask.kafka.enabled` | `true` | |
| `datamask.metrics.enabled` | `true` | Needs a Micrometer `MeterRegistry` in the context. |

A property left unset stays unset rather than taking a value of its own, so "not configured" and
"configured to the preset's value" cannot drift apart the day a preset changes.

`spring-boot-configuration-processor` runs on this module, so all of the above complete in an IDE.

## What an application contributes as beans

The properties cover what changes between environments. Everything else is a bean, picked up if it is
there:

| Bean | Effect |
|---|---|
| `TokenVault` | Where `TOKENIZE` keeps its surrogates. Without one, an in-memory vault. |
| `PolicyOverrides` | Masking for types that cannot be annotated — generated DTOs, third-party models. |
| `PiiDetector` | **Added** to the default detector set, not replacing it. |
| `Masker` | Registered by type, so a custom masker without a no-argument constructor can still be named from an annotation. |
| `MaskingObserver` | Any number. All of them see every event — see below. |
| `DataMaskBuilderCustomizer` | The last word, after everything above. |
| `DataMask` | Replaces the lot. The integrations then wire up whatever you built. |

`DataMaskBuilderCustomizer` is how a key that has to be fetched gets in:

```java
@Bean
DataMaskBuilderCustomizer kmsKey(KeyClient kms) {
    return builder -> builder.key(MaskKey.of(kms.dataKey("datamask")));
}
```

A key from a KMS or a vault cannot be a property, and routing it through one anyway would put the key
material into the environment — where `/env`, a heap dump and a crash report can all reach it.

Several `MaskingObserver` beans are composed rather than one being chosen. The engine takes a single
observer, an application usually wants two — metrics and an audit trail — and picking whichever bean
the container happened to hand over would silently drop the other. The one most likely to be dropped
is the alert on `onUnannotatedPii`.

## What each integration gets

**Jackson.** A `DataMaskModule` bean. Boot's own Jackson auto-configuration collects every
`JacksonModule` bean and registers it on the mappers it builds, so declaring it is the whole
integration.

**JDBC.** Every `DataSource` bean is wrapped in a `MaskingDataSource` by a `BeanPostProcessor` —
which covers a pool Boot configured, one the application declared, and each member of a multi-tenant
set, without this module knowing which auto-configuration produced it. `unwrap` still returns the
driver's own objects, so pool metrics, health indicators and code reaching for `PGConnection` keep
working. A `SqlExceptionSanitizer` bean is offered as well, for an exception that reached a
`@ControllerAdvice` rather than the driver.

The processor is `Ordered.LOWEST_PRECEDENCE`, so a pool decorator or a tracing proxy has already had
its say and the masking ends up **outermost** — an error from anything underneath still passes
through the sanitiser on its way out. One limit is Spring's and not this module's: a post-processor
that declares no order at all is registered after every ordered one, so it would still wrap this.
Declare `Ordered` on a decorator that needs to sit outside — and note that Spring buckets a
post-processor by the type its bean definition advertises, so a `@Bean` method returning
`BeanPostProcessor` hides the `Ordered` and lands in the unordered group.

**What wrapping breaks, and the way out.** The bean is a `MaskingDataSource` once wrapped, so an
injection point declared as the pool's own type stops resolving:

```java
@Autowired HikariDataSource pool;                              // NoSuchBeanDefinitionException
@Autowired DataSource ds; ds.unwrap(HikariDataSource.class);   // works, and keeps the masking
```

Prefer the second — it is what Boot's own pool metadata and health indicators do, which is why they
keep working. When a third party genuinely needs the bean to *be* the pool and cannot be changed,
name it:

```yaml
datamask:
  jdbc:
    excluded-beans: legacyReportingDataSource
```

That pool is then unmasked: its unique-constraint violations quote the rows they collided with again.
It is the last resort rather than the first, and every exclusion is logged at `WARN` at startup so
the decision is visible in the log of the environment it was made in.

**Logback, Log4j2, Kafka.** These three cannot be handed anything when they are built: `logback.xml`
is read before there is a container, and a Kafka producer instantiates its serializers from class
names through a no-argument constructor. Each module therefore keeps a static hand-off that its
plugins consult per event, and this module fills it in — a plugin that started under an ephemeral key
picks the real instance up from the next event onwards, and forgets it again when the context closes.

**Micrometer.** A `MicrometerMaskingObserver`, when the context already has a `MeterRegistry`.

| Meter | Tags | |
|---|---|---|
| `datamask.masked` | `category`, `strategy` | The shape of what the model actually carries. |
| `datamask.unannotated` | `category`, `detector` | **Alert on this.** |
| `datamask.failures` | — | Masking failed and the failure mode was applied. A bug. |
| `datamask.depth.limit.exceeded` | — | A graph deeper than the policy allows, masked short. |

`datamask.unannotated` is the earliest warning that a new field is leaking: a detector matched
something the domain model never described, which means the annotation is missing rather than the
masking working. A rate that moves after a deployment names the deployment that introduced the field.

The path is deliberately **not** a tag. It is the context an operator would most like — `Customer#email` —
and it is unbounded: it carries collection indices, and inside a map it carries keys, which in this
library's problem domain are sometimes the data itself. A tag built from it would blow up meter
cardinality *and* pipe PII into the metrics backend, which is precisely the sort of channel nobody
designs and nobody scrubs.

## Non-goals

**No appender is attached for you.** Where masking sits in a logging pipeline — which appenders it
covers, whether an async appender is inside it or outside — is a decision about that pipeline, and a
library on the classpath rewriting it would be a surprise found in production. Five lines in
`logback.xml` or `log4j2.xml` declare it; see [`datamask-logback`](../datamask-logback/README.md) and
[`datamask-log4j2`](../datamask-log4j2/README.md).

**No masking decision is made here.** Every one of them was already made in `datamask-core` or in the
integration module. This package only decides what exists — which is why it is the one module in the
library allowed to see several integrations at once, and why it still never reaches into
`ch.raph.datamask.infrastructure`.
