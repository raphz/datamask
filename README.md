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

## Strategies

| Strategy | Example output | Preserves |
|---|---|---|
| `REDACT` | `****` | nothing |
| `PARTIAL` | `****6827` | a trailing window |
| `HASH` | `~7Kd9fPqR2xLmA0Zt` | equality, across services |
| `TOKENIZE` | `tok_iban_9fB2…` | reversibility, via a vault |
| `NULLIFY` | *(absent)* | nothing, not even presence |
| `EMAIL` | `j*******@e******.com` | shape and TLD |
| `NAME` | `J***-P***** D*****` | initials |
| `IBAN` | `CH93 **** **** **** *295 7` | country, check digits, last four |
| `PAN` | `**** **** **** 1111` | last four (PCI-DSS 3.3) |
| `PHONE` | `+41*******67` | country code, last two |
| `IP` | `192.168.4.0` | network prefix |
| `DATE_GENERALIZE` | `1985-01-01` | the year |
| `PRESERVE_FORMAT` | `031 4820 7715` | length and character classes |
| `SCAN` | masks only the PII inside prose | the surrounding text |

`AUTO` — the default — resolves from the declared `category`, then from a `@PII` annotation on the
declared type, then from what the value's content is detected to be, and finally falls back to
`REDACT`.

## Categories

Annotating the **category** and leaving the strategy on `AUTO` is the recommended style: the
category is what a developer can state confidently, while the strategy is a policy decision that
may differ between environments.

`EMAIL` · `PHONE` · `FULL_NAME` · `POSTAL_ADDRESS` · `DATE_OF_BIRTH` · `IBAN` · `ACCOUNT_NUMBER` ·
`BIC` · `PAN` · `CARD_VERIFICATION_VALUE` · `CARD_EXPIRY` · `NATIONAL_ID` · `TAX_ID` ·
`IDENTITY_DOCUMENT` · `CUSTOMER_ID` · `CREDENTIAL` · `BIOMETRIC` · `IP_ADDRESS` · `DEVICE_ID` ·
`GEO_LOCATION` · `FINANCIAL_AMOUNT` · `FREEFORM_TEXT`

## Policy

Annotations say what the data *is*. The policy says how strictly this deployment treats it, so one
annotated domain model can be logged verbosely in a sandbox and strictly in production without any
code change.

```java
DataMask.builder()
        .secret(secret)
        .policy(MaskingPolicy.strict())        // or .relaxed(), or build your own
        .observer(metricsObserver)
        .overrides(policyForGeneratedDtos)     // for classes you cannot annotate
        .build();
```

`strict()` masks everything annotated, scans free text, and redacts on failure. `relaxed()` hides
only high-sensitivity data and leaves prose alone — card numbers and credentials still never
appear.

## Types it can mask

- **Records** — rebuilt through the canonical constructor.
- **Beans** — through an all-arguments constructor matching field order (what Lombok's
  `@AllArgsConstructor` and Jackson's `@ConstructorProperties` produce), or a no-argument
  constructor plus field writes.
- **Collections, maps, arrays, `Optional`** — traversed, with cycle detection and depth and size
  bounds.
- **Single-component value objects** — `record Email(String value)` has its contents masked and
  stays an `Email`.
- **Numeric and temporal PII** — a masked `BigDecimal` becomes `ZERO`, a masked `LocalDate` becomes
  the first of its year, so the rebuilt object still type-checks.

A graph containing no PII is returned as the *same instance* — no allocation, no copy.

## Escape hatches

```java
@NoMask(justification = "ISO currency code identifies no one")
String currency
```

The justification is mandatory. An unexplained exemption on a PII-bearing type is exactly the
change that should not pass review unnoticed.

For a format the library does not know:

```java
@PII(masker = ContractReferenceMasker.class) String reference
```

A `Masker` receives a `MaskContext` giving it the engine's keyed pseudonymisation and tokenisation,
so custom maskers depend only on the dependency-free `datamask-api` module.

## Masking JSON on the way out

`datamask-jackson` masks while the document is written, so no masked copy of the object graph is
built and no call site changes:

```java
ObjectMapper mapper = JsonMapper.builder()
        .addModule(new DataMaskModule(dataMask))
        .build();
```

Two things are hooked. Declared PII is handled from the compiled plan — `@PII`, `@NoMask` and any
policy override have already collapsed into one decision per property, taken once when the
serializer for the type is built rather than on every write. Everything else goes through the
detectors, including strings inside lists, map values and the root of the document, so an IBAN that
ended up in a free-text field is still caught. That second half follows
`MaskingPolicy#scanUnannotatedText` and disappears entirely when it is off.

A property declaring both `@PII` and `@JsonSerialize(using = ...)` is masked. A custom renderer is
not a way around the annotation.

Deserialization is deliberately untouched: this protects what leaves the process, and masking on the
way in would destroy data the application is meant to store.

## Modules

| Module | Status |
|---|---|
| `datamask-api` | **implemented** — annotations and SPI, zero dependencies |
| `datamask-core` | **implemented** — engine, strategies, detectors, policy |
| `datamask-bom` | **implemented** |
| `datamask-jackson` | **implemented** — Jackson 3, masks at serialization time |
| `datamask-logback` / `datamask-log4j2` | planned |
| `datamask-opentelemetry` | planned — span attributes and log records |
| `datamask-kafka` | planned — serializer and interceptors |
| `datamask-jdbc` / `datamask-jpa` | planned — bind parameters, PostgreSQL error details, converters |
| `datamask-ai` | planned — prompt sanitisation with reversible placeholders |
| `datamask-spring-boot-starter` | planned |
| `datamask-processor` | planned — compile-time validation of `@PII` |

`datamask-api` is deliberately dependency-free so a domain module can declare `@PII` without taking
on the engine, a reflection library, or a logging framework.

## Requirements

Java 21 or later. Built and tested on JDK 25.

## Releasing

Published to Maven Central by the **Release** workflow. Setup and procedure are in
[docs/RELEASING.md](docs/RELEASING.md).

## Licence

Apache License 2.0.
