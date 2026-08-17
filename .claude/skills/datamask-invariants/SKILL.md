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

## 6. The observer signal that matters

`MaskingObserver.onUnannotatedPii` fires when a detector finds PII in a value nobody annotated. **It
is the earliest warning that a new field is leaking**, and alerting on it is what turns this library
from a mask into a control. Keep it wired through every integration; never make it silent.

`MaskingObserver` implementations run on the masking hot path and must be cheap and non-throwing.

## 7. Exceptions must not become the leak

`MaskingException` messages identify the **path and the type, never the value**. Apply the same rule
to any new diagnostic, log line or error added anywhere in this library.

## 8. Never mutate the input

Masking returns a copy. The caller is still using the original — it is the live domain object the
business logic is operating on.

## 9. Things that look like simplifications but are not

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
- The never-partially-reveal guard exists in three layers on purpose: `PiiDescriptor` (keep = 0 and
  CRITICAL sensitivity), `MaskingEngine.hardened` (revealing strategies become REDACT), and the
  first line of every revealing masker. Removing any one of them is not a cleanup.

## Reviewing a change here

Ask: *if this code is wrong, does the output contain more of the original value than before?* If the
answer is yes or "not sure", it needs a test that asserts the raw value is absent — use
`.doesNotContain(rawValue)`, not just an equality check on the expected mask.
