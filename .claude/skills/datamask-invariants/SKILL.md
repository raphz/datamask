---
name: datamask-invariants
description: The security and compliance rules DataMask must never break — fail-closed behaviour, keyed pseudonymisation, categories that are never partially revealed, PCI-DSS and GDPR constraints, and what makes a change to this library dangerous. Triggers on — changing a masker, the engine, failure handling or key handling, reviewing a PR, adding a strategy or category, relaxing a check, "is this safe", anything where masked output could become less masked.
---

# DataMask — invariants

This is a security library. A bug here does not throw an exception; it silently writes a customer's
IBAN into a log that ships to a third-party aggregator. **Every one of these rules exists because
breaking it is undetectable in normal testing.**

## 1. Fail closed, always

Every error path must produce **less** information than it started with, never more.

- A masker that throws yields the redaction placeholder, not the value it failed to mask.
- A value object whose constructor rejects the masked string yields `null`, not the original.
- Depth or size limits exceeded yields `null` or a truncated collection, not unmasked data.
- An inaccessible or unrebuildable type yields a thrown `MaskingException`, not a pass-through.

`FailureMode.PASS_THROUGH` exists only for local debugging, and it **deliberately refuses to pass a
value through when a masker fails** — it throws instead. It applies only to structural failures.

`MaskingEngine.descendObject` rethrows `MaskingException` rather than treating it as a structural
failure. Without that, the generic `catch (Throwable)` silently undoes `FailureMode.THROW` for every
nested field. This was a real bug, found by a test; do not "simplify" it away.

## 2. Pseudonymisation is keyed, never a bare digest

`HASH` is HMAC-SHA-256 with a secret. An unkeyed SHA-256 of an IBAN, a phone number or an AVS number
is reversible by enumeration in seconds — the input space is tiny — and **would not qualify as
pseudonymisation under GDPR Article 4(5)**.

- `MaskKey.ofSecret` rejects anything under 16 bytes. Do not lower this. It also runs the secret
  through HKDF-SHA-256 (`javax.crypto.KDF`, deterministic, fixed salt/info) before it becomes the
  HMAC key — a configured secret is a human-chosen passphrase, and using it directly would let one
  known (value, pseudonym) pair brute-force it offline. Do not remove the derivation; changing the
  salt or info strings changes every pseudonym.
- **Every pseudonym names the key that made it** — `~<keyId>:<digest>`, the id HKDF-derived from the
  material so two processes agree with nothing configured. Do not remove it and do not shorten the
  format: without the id, a key rotation silently turns every previously written pseudonym into an
  unjoinable stranger — no error, nothing in a log. `HmacPseudonymizer.matches` is what makes a
  rotation survivable, and it needs the id to know which key to recompute under.
- `MaskKey.ephemeral()` is for tests and local development. It is safe but makes pseudonyms
  incomparable across instances and restarts, removing the reason to prefer `HASH` over `REDACT`.
- **Never ship a built-in default key.** A publicly known key makes every pseudonym trivially
  reversible. The Spring auto-configuration must fail fast when no secret is configured rather than
  silently fall back.
- `MaskKey.toString()` must never expose key material.

## 3. Some categories are never partially revealed

`PiiCategory.neverPartiallyReveal()` covers `CARD_VERIFICATION_VALUE`, `CREDENTIAL`, `BIOMETRIC` and
`CARD_EXPIRY`. This is enforced in the **compact constructor of `PiiDescriptor`**, which forces
`keep = 0` — deliberately not left to each masker to remember, and it overrides both the annotation
and any policy. An annotation asking `@PII(category = CARD_VERIFICATION_VALUE, keep = 3)` is
silently corrected, and there is a test for exactly that.

Storing or logging a CVV at all is prohibited outright by PCI-DSS; partial disclosure is not a
lesser version of that, it is the same finding.

## 4. PCI-DSS on card numbers

`PanMasker` reveals only the last four digits. The standard permits first-six-and-last-four, but the
last four is what identifies a card to its holder, so that is the default and the safer one.

A value that **fails** the Luhn check is masked all the same. Being wrong about what a card number
looks like must never be a route to leaking one.

## 5. Detection requires check digits

`Detectors.paymentCard`, `iban`, `swissAhv` and `bic` all set `requireChecksum = true`. Without it
every order reference and correlation id in a log is reported as a card number, scanning becomes
unusable in production, and someone turns it off — which is the actual failure mode.

`BIC` additionally validates the country code against `Locale.getISOCountries()` **and** requires a
digit or a trailing `XXX`. The country check alone was not enough: any uppercase word whose fifth and
sixth letters spell an ISO code passed it, and log prose does that constantly — `CHECKING` is
Kiribati, `DEUTSCHE` the Seychelles, `APPLICATION` Canada, `CUSTOMER` Oman. The trade is deliberate
and is the one to keep making: an all-letter eight-character BIC like `DEUTDEFF` is no longer found in
free text, but it is still masked wherever it is declared, and a scanner that garbles every
capitalised word gets switched off — taking every other detector with it.

When adding a detector: if the identifier has a check digit, use it and require it. If it does not,
require enough surrounding structure that false positives stay rare (see `internationalPhone`, which
only matches numbers written with a `+`).

### A detector gate can leak without anything going red

`RegexDetector.gatedBy` / `PiiDetector.mightMatch` skips a detector whose pattern provably cannot
match — an `@` for an address, twelve digits for a card, six consecutive capitals for a BIC. That is
where most of the scanning cost went, and it is also the one construct in this library that can be
wrong with **no symptom at all**: the pattern still works, the checksum still holds, every test of
the detector still passes, and the value is simply never examined.

- The condition must be **necessary** for the pattern to match. Derive it from the pattern, state
  what the pattern cannot do without, and never what a value usually comes with.
- **Unsure means `true`.** A wrong `true` costs one pattern match. `TextSignals.contains` follows the
  same rule and reports any character it does not track as present.
- A new gate is not written until its positive fixtures are in `DetectorGateTest`, which holds every
  fixture three ways: the detector finds it, the gate admits it, and the engine removes it from the
  text. The third is what catches a gate and a pattern that have drifted apart.

## 6. The observer signal that matters

`MaskingObserver.onUnannotatedPii` fires when a detector finds PII in a value nobody annotated. **It
is the earliest warning that a new field is leaking**, and alerting on it is what turns this library
from a mask into a control. Keep it wired through every integration; never make it silent.

**Do not route anything else through it.** A value explicitly declared `FREEFORM_TEXT` or `SCAN`
produces detector hits on every single request — that is the feature working — and reporting those
here made the signal fire constantly, which is how a signal stops being alerted on. Declared text
goes to `onScanned`, via `TextSanitizer.sanitizeDeclared`; undeclared text goes to
`onUnannotatedPii`, via `sanitize`. When adding an integration, ask which one each site is and say so
in a comment: every integration so far is `onUnannotatedPii`, because nothing a log line, a bind
parameter, a Kafka header or an untyped `JsonNode` carries was ever declared as free text.

`onCollectionTruncated` is likewise separate from `onDepthLimitExceeded`. A deep graph and a runaway
collection want different responses, and reporting both as depth — under a synthesised index that
differed between the list and map cases — meant neither could be counted.

**Every path carries a scheme.** `<module>:<site>[/<detail>]`, documented in `datamask-core/README.md`.
An integration handing a whole object to the engine must use `MaskingEngine.mask(value, rootPath)`,
not `mask(value)`: the root is the one site it cannot otherwise name, and a structural failure there
is reported against the empty string, which no rule can attribute.

`MaskingObserver` implementations run on the masking hot path and must be cheap and non-throwing.

## 7. Exceptions must not become the leak

`MaskingException` messages identify the **path and the type, never the value**. Apply the same rule
to any new diagnostic, log line or error added anywhere in this library.

## 8. Never mutate the input

Masking returns a copy. The caller is still using the original — it is the live domain object the
business logic is operating on.

## 9. An override outranks the code's author

`PolicyOverrides.drop(Type.class, "member")` is decided **before** `@NoMask`, and that order is
deliberate. `@NoMask` is a claim by whoever wrote the class that a member holds nothing personal; an
override is the deployment saying otherwise. The deployment is the one being audited, so it wins.
Reversing this would let an annotation nobody re-reviewed veto a control someone configured
deliberately.

A drop is not the same as masking to a placeholder: the member is left out entirely, so a serializer
omits the property and not even its existence is disclosed.

## 10. Things that look like simplifications but are not

- The no-change short-circuit in `descendObject` (`changed ? rebuild : value`) is what makes
  unrebuildable PII-free types work and what avoids allocating on clean graphs.
- `Types.isLeaf(Object.class)` returns `false`, and interfaces return `false`. They say nothing about
  the runtime value; stopping there would skip masking entirely.
- `TextSanitizer.maskSpan` maps `AUTO`/`SCAN` to `REDACT`. Re-entering the scanner would not
  terminate.
- `Mac` is created per call in `HmacPseudonymizer`, not cached in a field or a `ThreadLocal`. It is
  not thread-safe, and a `ThreadLocal` would pin memory per virtual thread.
- An **object** cycle's back-reference becomes `null` in the masked copy, never the original
  instance — "the members were already masked on the way in" is wrong, because they were masked into
  the *copy*; the original still carries raw PII. A **container** is different and must stay
  different: it registers its copy before walking, so the back-reference points at the copy. Do not
  "unify" these — nulling a container cycle loses shape for no gain, and unrolling one (which is what
  happened before containers were registered at all) is exponential.
- A masked element that a copy refuses — null in an `ArrayDeque`, null in a `ConcurrentHashMap` — is
  dropped, not propagated. Letting the refusal escape takes the whole enclosing object down a failure
  path over one element.
- `RejectingTokenVault` is the default vault and it **throws**. That is not an oversight to "fix" by
  installing `InMemoryTokenVault`: the engine turns the throw into redaction plus `onFailure`, so an
  unconfigured application gets masking and a signal. A working default vault means raw PII in a heap
  map and a `detokenize` that reverses masking for anyone who can reach the bean.
- `MaskPlan.failed` is distinct from `MaskPlan.opaque`, and `isOpaque()` must never be true for a
  failed plan: an opaque type is proven safe to pass through, a failed one only *looks* empty
  because its members could not be read.
- `TextSanitizer.scan` keeps the uncovered tail of an overlapping finding as a low-confidence
  fragment, and `maskSpan` redacts any non-confident finding outright. Dropping overlapped findings
  whole would disclose their tails; format-masking a fragment would reveal characters at positions
  chosen for a whole value.
- `Masker.supports` is asked about the value's **runtime** class, not the declared type of the member
  it came from. A member declared `Object` or `CharSequence` says nothing about what a masker must
  handle, and a masker answering "no" is replaced by full redaction — so asking about the declared
  type quietly redacted values that had a working masker. This is not a weakening: the fallback is
  still redaction, and the declared type still decides what the result has to fit.
- The never-partially-reveal guard exists in three layers on purpose: `PiiDescriptor` (keep = 0 and
  CRITICAL sensitivity), `MaskingEngine.hardened` (revealing strategies become REDACT), and the
  first line of every revealing masker. Removing any one of them is not a cleanup.

## Bound every container you walk

The engine bounds collections and maps at `maxCollectionElements`, but an integration that walks a
structure of its own has to do it too, and the ones that forgot were not obvious. A suppressed
exception list is the worked example: a batch failing item by item suppresses one exception per item,
each with its own cause chain, and both logging modules walked all of them. If you are iterating
something whose size the application controls, bound it and report `onCollectionTruncated`. Dropping
the tail discloses nothing; not bounding it turns a log statement into an outage.

**Text is a container too.** Scanning is linear in characters, so `MaskingPolicy.maxTextLength`
bounds it and everything past the cap is redacted rather than emitted unscanned — the direction that
matters, because passing the tail through would make "put 8 KB of prose in front of it" a way around
the scanner. A bound that cuts at a fixed offset can cut *through* a value, so `TextSanitizer` reads a
short margin past the cap and moves the cut back to the start of any finding that straddles it.
Without that, the cap would leave the first twelve digits of a card number in the output: a partial
disclosure created by the protection itself. Any new bound on text owes the same question — what does
a value sitting exactly on the boundary look like afterwards?

## Reviewing a change here

Ask: *if this code is wrong, does the output contain more of the original value than before?* If the
answer is yes or "not sure", it needs a test that asserts the raw value is absent — use
`.doesNotContain(rawValue)`, not just an equality check on the expected mask.
