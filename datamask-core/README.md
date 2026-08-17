# datamask-core

**The engine: strategies, categories, detectors, policy and the object-graph walk.**

```java
DataMask dataMask = DataMask.builder().secret(System.getenv("DATAMASK_SECRET")).build();

Customer safe = dataMask.mask(customer);
String safeText = dataMask.maskText(supportNote);
List<PiiFinding> found = dataMask.scan(payload);
```

Instances are immutable and thread-safe, and are meant to be created once per application.

## Strategies

| Strategy | Example output | Preserves |
|---|---|---|
| `REDACT` | `****` | nothing |
| `PARTIAL` | `****6827` | a trailing window |
| `HASH` | `~a3Kd9Q:7fPqR2xLmA0Ztb1Xw` | equality, across services |
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

`AUTO` — the annotation default — resolves in this order, and the order matters:

1. the declared `category`'s own default strategy;
2. a `@PII` annotation on the declared **type**, so a bare `@PII Email email` defers to whatever
   `Email` declares;
3. what the value's content is **detected** to be;
4. `REDACT`. Never "pass through".

## Categories

Annotating the **category** and leaving the strategy on `AUTO` is the recommended style: the category
is what a developer can state confidently, while the strategy is a policy decision that may differ
between environments.

`EMAIL` · `PHONE` · `FULL_NAME` · `POSTAL_ADDRESS` · `DATE_OF_BIRTH` · `IBAN` · `ACCOUNT_NUMBER` ·
`BIC` · `PAN` · `CARD_VERIFICATION_VALUE` · `CARD_EXPIRY` · `NATIONAL_ID` · `TAX_ID` ·
`IDENTITY_DOCUMENT` · `CUSTOMER_ID` · `CREDENTIAL` · `BIOMETRIC` · `IP_ADDRESS` · `DEVICE_ID` ·
`GEO_LOCATION` · `FINANCIAL_AMOUNT` · `FREEFORM_TEXT`

`CARD_VERIFICATION_VALUE`, `CREDENTIAL`, `BIOMETRIC` and `CARD_EXPIRY` are **never partially
revealed**. That is enforced centrally, so an annotation asking to keep three characters of a CVV is
silently corrected rather than honoured.

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

`strict()` masks everything annotated, scans free text, and redacts on failure. `relaxed()` hides only
high-sensitivity data and leaves prose alone — card numbers and credentials still never appear.

`MaskingPolicy` also bounds the walk: `maxDepth`, `maxCollectionElements`, `scanUnannotatedText` and
`maskMapKeys` — **on** under `strict()`, off under `relaxed()`. A map keyed by email address is a
common enough shape that leaving keys alone in production was the wrong default; the cost is that
masking a key changes lookup semantics in the masked copy, which is why `relaxed()` still skips it.

## Content detection

Annotations cover the data a developer knew about. Detectors cover the rest: the payment reference a
customer typed their own IBAN into, the exception message quoting a row, the support note pasted into
a ticket.

Detection uses **check digits** — Luhn, IBAN mod-97, AVS EAN-13 — rather than shape alone, so an order
reference is not reported as a card number. Without that, every correlation id in a log is a finding,
scanning becomes unusable in production, and somebody turns it off; that is the real failure mode.

Every detector hit on unannotated data is reported to `MaskingObserver.onUnannotatedPii`. **That is
the signal worth alerting on**, because it is the earliest warning that a new field is leaking.

## Types it can mask

- **Records** — rebuilt through the canonical constructor.
- **Beans** — through an all-arguments constructor (what Lombok's `@AllArgsConstructor` and Jackson's
  `@ConstructorProperties` produce), or a no-argument constructor plus field writes. Parameters are
  matched to fields by **name** where the class carries them (`-parameters` or
  `@ConstructorProperties`), so the constructor may take them in any order. Without names, a match is
  only accepted while the field types are distinct — two same-typed parameters would otherwise be
  matched by position, and a constructor declaring them the other way round would swap two values in
  every masked copy. That case is refused rather than guessed.
- **Collections, maps, arrays, `Optional`** — traversed, with cycle detection and depth and size
  bounds.
- **Single-component value objects** — `record Email(String value)` has its contents masked and stays
  an `Email`. If the constructor validates and rejects the masked string, the field becomes `null` —
  lossy on purpose, rather than disclosing.
- **Numeric and temporal PII** — a masked `BigDecimal` becomes `ZERO`, a masked `LocalDate` becomes
  the first of its year, so the rebuilt object still type-checks.

A graph containing no PII is returned as the *same instance* — no allocation, no copy. The same holds
for `maskText`, which returns the same `String` when nothing matched.

The original object is never mutated: the caller is still using it.

## Performance

A `MaskPlan` is derived per class and cached in a `ClassValue`, so after the first instance masking is
a handful of `MethodHandle` invocations plus one constructor call.

`MaskPlanCompiler` is a port with two implementations, and `DataMask.builder()` picks between them.
`ReflectiveMaskPlanCompiler` is the one just described. `GeneratedMaskPlanCompiler` answers from plans
that [`datamask-build-processor`](../datamask-build-processor/README.md) worked out at compile time —
a direct call per member and a direct constructor invocation, no reflection at all — and sends
everything it has no plan for to the reflective one. Adding the processor to the annotation path is
the only thing an application does; `.compiler(...)` on the builder is there for anything else.

A policy override turns generation off **for the types it reaches** — the type a member override
names, a type named outright, and any type holding a member whose declared type an override names. A
generated plan resolved `@PII` before `PolicyOverrides` existed, so answering from one would silently
ignore the override. Only those types pay for it: one override for one DTO does not return the whole
application to reflection.

## Keys

`HASH` and `TOKENIZE` need a secret of at least sixteen bytes, run through HKDF-SHA-256 before it
becomes the HMAC key. Prefer `secret(char[])` over `secret(String)` — a `String` cannot be wiped, and
stays readable in any heap dump taken before it is collected.

`MaskKey.ephemeral()` exists for tests and local development; it is safe but makes pseudonyms
incomparable across instances and restarts, which removes the reason to prefer `HASH` over `REDACT`.
There is deliberately **no built-in default key** — a publicly known key makes every pseudonym
trivially reversible.

### Rotating one

A pseudonym reads `~<keyId>:<digest>`, where the key id is derived from the material itself, so two
processes sharing a secret compute the same id with nothing to configure. That is what makes a
rotation survivable: a pseudonym written before it is recognisable as such, rather than a value that
silently stops joining to anything.

```java
DataMask.builder()
        .secret(current)
        .previousSecret(retiring)        // repeatable; keep for as long as old data is joined
        .build();

dataMask.pseudonymMatches(iban, pseudonymWrittenLastYear);   // true, via the keyring
```

`MaskKey.forPurpose("export")` derives a separate subkey from the same secret, so a pseudonym issued
for one purpose cannot be joined against one issued for another. `destroy()` wipes the material.

## Tokenisation needs a vault you chose

`TOKENIZE` is the one strategy that keeps the original value, so there is no safe default and the
library does not pretend otherwise. Without `Builder.vault(...)`, tokenisation **refuses**: the value
is redacted and `MaskingObserver.onFailure` is told. `InMemoryTokenVault` is opt-in, bounded by
capacity and a fifteen-minute time to live, and is meant for the request-scoped round trip — mask a
prompt, put the real values back into the answer. Card data belongs in a PCI-scoped vault behind your
own `TokenVault`.
