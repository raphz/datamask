# DataMask

**PII never accidentally reaches logs, traces or AI prompts.**

Declare what your data *is*, once, on your domain model. DataMask makes sure it can't leak
anywhere else.

```java
public record Customer(
        @PII Email email,
        @PII(strategy = HASH) String iban,
        String country) { }
```

```java
DataMask dataMask = DataMask.builder().secret(System.getenv("DATAMASK_SECRET")).build();

Customer safe = dataMask.mask(customer);
// Customer[email=Email[value=j*******@e******.com], iban=~7Kd9fPqR2xLmA0Zt, country=CH]
```

The original object is untouched. The masked copy is the same type, so it drops straight into a
log statement, a span attribute, a Kafka record or a model prompt.

## Why I built this

Twenty-five years of building systems for banks and fintechs taught me one thing about personal
data: **it almost never escapes through the front door.** The database is encrypted at rest, the
traffic is on TLS, the access paths are reviewed and audited. What leaks is the *diagnostics* —
the log line, the stack trace, the span attribute, the message on the queue, the support export,
and now the prompt sent to a model provider. Nobody designs those. They accumulate.

The usual answer is to bolt masking on downstream: a set of regexes in the log appender, a
scrubber in the aggregator, a redaction rule in the collector. I have written that code more than
once, and it always decays the same way. It works the day it ships, and then somebody adds a
field, generates a DTO, or lets a record's `toString()` reach an exception message — and the data
walks straight past a filter that was never told about it. Downstream scrubbing has to *guess*
what a string is. Only the domain model knows.

The leaks I have met repeatedly are unglamorous. A unique-constraint violation whose PostgreSQL
detail echoes the offending row verbatim. A customer who typed their own IBAN into the payment
reference, which is a free-text field and is logged as one. A correlation header carrying a
customer identifier through every hop. A `toString()` written for a debugging session in 2014 and
never revisited. None of these are exotic; each one is an afternoon of someone's carelessness and
a very long conversation with the compliance team afterwards.

That conversation is why the cost is asymmetric. Once an IBAN is in a third-party aggregator's
index it is retained, replicated across regions, and inside somebody else's backups. Remediation
is expensive, and proving it was complete is harder than doing it. Under GDPR and PCI-DSS the
finding does not care that the leak was accidental.

So DataMask inverts the order. You declare what a value **is** exactly once, on the domain model —
the only place in the system that actually knows — and every downstream channel inherits that
truth instead of re-deriving it. Every error path produces less information than it started with,
because in this library a bug does not throw an exception, it silently writes a customer's account
number somewhere it cannot be recalled from. Swiss identifiers (AVS/AHV numbers, Swiss IBANs) are
first-class because that is the estate I know best, and because a library that is vague about the
local formats is a library that gets switched off.

I wanted the compliance officer reading the code and the SRE reading the masked log to both be
satisfied by the same annotation. That is the whole design.

## Why the defaults are what they are

**`HASH` is keyed, not a bare digest.** An unkeyed SHA-256 of an IBAN or a phone number is
reversible by enumeration in seconds — the input space is tiny — and would not count as
pseudonymisation under GDPR Article 4(5). DataMask uses HMAC-SHA-256 with a secret you supply, so
the surrogate is stable across services and restarts (you can still follow one customer through a
log aggregator) but not recoverable.

**Masking fails closed.** Every error path produces *less* information than it started with. A
masker that throws yields the redaction placeholder, not the value it failed to mask. There is a
`PASS_THROUGH` failure mode for local debugging, and it deliberately refuses to pass a value
through when a masker fails.

**Annotations are not the only line of defence.** Content detectors scan free-form text for PII
that nobody declared — the payment reference a customer typed their own IBAN into, the exception
message quoting a row. Detection uses check digits (Luhn, IBAN mod-97, AVS EAN-13) rather than
shape alone, so an order reference is not reported as a card number. Every detector hit on
unannotated data is reported to the observer: **that signal is the one worth alerting on**, because
it is the earliest warning that a new field is leaking.

**Some things are never partially revealed.** A card verification value, a credential or biometric
data is redacted whole, even if an annotation or a policy asks to keep some of it.

## Modules

Each module documents itself. This page stays deliberately short; the detail lives next to the code.

| Module | Status | What it protects |
|---|---|---|
| [`datamask-api`](datamask-api/README.md) | **implemented** | The annotations and the SPI. Zero dependencies. |
| [`datamask-core`](datamask-core/README.md) | **implemented** | The engine: strategies, categories, detectors, policy. |
| [`datamask-bom`](datamask-bom/README.md) | **implemented** | One version for every module. |
| [`datamask-jackson`](datamask-jackson/README.md) | **implemented** | JSON, masked as the document is written. |
| [`datamask-jdbc`](datamask-jdbc/README.md) | **implemented** | PostgreSQL error details and bind parameters. |
| [`datamask-log4j2`](datamask-log4j2/README.md) | **implemented** | Log parameters, message bodies, thread context, exception messages. |
| `datamask-logback` | **implemented** | The same, through Logback's own extension points. |
| [`datamask-kafka`](datamask-kafka/README.md) | **implemented** | Records and headers, before they reach a topic. |
| `datamask-opentelemetry` | planned | Span attributes, events and log records before export. |
| `datamask-jpa` | planned | `AttributeConverter`s for pseudonymised columns at rest. |
| `datamask-ai` | planned | Prompt sanitisation with reversible placeholders. |
| [`datamask-spring-boot-starter`](datamask-spring-boot-starter/README.md) | **implemented** | The dependency a Spring Boot application adds. |
| [`datamask-spring-boot-autoconfigure`](datamask-spring-boot-autoconfigure/README.md) | **implemented** | One `DataMask` from properties, wired into every module on the classpath. |
| [`datamask-processor`](datamask-processor/README.md) | **implemented** | Compile-time validation of `@PII` usage. |

`datamask-api` is deliberately dependency-free so a domain module can declare `@PII` without taking
on the engine, a reflection library, or a logging framework.

That, and the rest of the dependency direction between the modules, is enforced rather than reviewed:
[`datamask-architecture-tests`](datamask-architecture-tests/README.md) holds every module on its test
classpath and fails the build when it drifts. It is not in the table because it is not published.

## Requirements

Java 21 or later. Built and tested on JDK 25.

## Building

```bash
./gradlew build
```

One command is the whole verdict: it compiles, checks formatting, runs the tests, checks the
architecture rules, and enforces **80% aggregated coverage** across every module. `./gradlew
spotlessApply` fixes formatting; the coverage report lands in
`build/reports/jacoco/testCodeCoverageReport/html/index.html`.

## Releasing

Published to Maven Central by the **Release** workflow. Setup and procedure are in
[docs/RELEASING.md](docs/RELEASING.md).

## Licence

Apache License 2.0.
