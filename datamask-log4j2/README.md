# datamask-log4j2

**Masks the log event before an appender ever sees it, so PII never reaches a file, a console or a log
shipper.**

```xml
<Rewrite name="MASKED">
    <AppenderRef ref="CONSOLE"/>
    <AppenderRef ref="FILE"/>
    <DataMask secret="${env:DATAMASK_SECRET}"/>
</Rewrite>

<Root level="INFO">
    <AppenderRef ref="MASKED"/>
</Root>
```

Nothing else changes. The appenders keep their layouts, the domain model keeps its `@PII` annotations,
and no call site is touched.

Logs are the most common way PII escapes a banking system, and the reason this library exists. A log
line is also assembled from more places than a DTO is, and each of them leaks differently — which is
why all of them are handled here.

## What is masked

| Where | How | Why it matters |
|---|---|---|
| **Parameters** | Masked from their declarations, before formatting | `logger.info("paid {}", customer)` masks the customer's IBAN because the field says what it is — and the rendered line never contained it |
| **The message** | Scanned by the detectors | `logger.info("email " + email)` has no parameter to mask and no annotation to read |
| **Thread context map** | Values scanned, keys left alone | The quietest of the four: attached to every line of a request, by code nobody re-reads |
| **The thrown exception** | Replaced by the same type with a masked message, down the cause chain and the suppressed list | `Key (email)=(john@x.com) already exists` is a constraint violation answering with the row that caused it |
| **Map and object messages** | Masked value by value, keeping the message type | Structured logging is a map of values, therefore a map of things to mask |

Every detector hit is reported to `MaskingObserver.onUnannotatedPii` with where in the event it was
found — `…PaymentService.context.customer`, `…PaymentService.arg1` — which is the earliest warning that
a field has started carrying PII nobody classified, and enough to find the code that put it there.

## An event that carried nothing is forwarded as itself

The engine and the text sanitiser both return the **same instance** when nothing was masked, so
`LogEventMasker` compares references and hands back the original event. A PII-free log line pays for the
scan and nothing else — no copy, no allocation. That is what makes this affordable on a path that runs
on every line.

## Rewriting the event, not the text

Log4j2 offers both, and the difference is coverage. `MaskingRewritePolicy` replaces the event, so
everything downstream — every appender, layout and shipper — sees only the masked one; a JSON layout
writing the exception and the context map is covered exactly as a pattern is.

`MaskingMessagePatternConverter` is the alternative for a configuration that cannot be restructured:

```xml
<PatternLayout pattern="%d %-5level %logger{36} - %maskedMessage%n"/>
```

Swapping `%msg` for `%maskedMessage` is a one-line change. **It reaches the message only.** `%X` renders
the context map and `%ex` the exception, each through a converter of its own, and both would still print
what they were given. Use the rewrite policy whenever more than one layout, appender or shipper sees the
events.

## Exceptions are replaced, not wrapped

Log4j2 renders an exception through a `ThrowableProxy` built from a real `Throwable`, and both read the
message from fields nothing can intercept. Masking one therefore means replacing it — and the
replacement has to be of the **same type**, or the log would name the wrong exception, which is the one
thing a reader trusts a stack trace for.

So the type is reconstructed through its `(String, Throwable)` constructor, then `(String)`, with the
original's frames copied onto it. A type with neither gets a stand-in reporting the original class name
in its `toString()`. What is lost is state the exception held in fields of its own — a SQL state, an
error code; no layout prints it, and losing it is the fail-closed direction.

## Where the DataMask comes from

In order: the `secret` attribute on the plugin, then `DataMaskLog4j2.install(dataMask)`, then a fallback
of strict masking under an ephemeral key that reports an error to the status logger.

```java
DataMaskLog4j2.install(DataMask.builder().secret(System.getenv("DATAMASK_SECRET")).build());
```

The install point exists because of when logging starts: a log4j2 configuration is read before an
application has a container, a context or any beans, so a plugin cannot be handed anything at that
point. It looks instead, per event, for something installed since — which is why an instance installed
after the first log line is still picked up.

The fallback is safe rather than convenient. Everything is masked; what an ephemeral key costs is that a
`HASH` pseudonym differs after a restart, which removes the reason to prefer it over `REDACT`. The same
is true of a secret log4j2 rejected as too short: masking continues, loudly, because dropping every log
line would be the larger outage.

## Plugin registration

Log4j2's own annotation processor writes `Log4j2Plugins.dat` into this jar at build time, so the plugins
are found through the descriptor every configuration already reads. Nothing has to scan a package for
them — a scan is deprecated, silent when it fails, and would leave masking switched off with no error.

## Failing closed

A logging call must not fail the business operation, and must not fall back to text it could not mask.
When masking itself fails — including under `FailureMode.THROW` — the event keeps its level, logger,
thread and timestamp, and its message becomes `**** [datamask withheld this message: masking failed]`.
The notice names no exception message, because the exception was raised while handling a value and may
well quote it.

## Tests

52, all asserting the raw value is **absent** from what a layout renders rather than only that the
masked form is present. They cover the declared strategies, bare values a detector recognises, map and
object messages, the context map, cause chains and suppressed exceptions, the same-type reconstruction
and its stand-in, both policies, the observer paths, plugin registration through the generated
descriptor, and the fail-closed paths.
