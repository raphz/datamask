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

## Where a finding says it came from

Every path this module reports follows the grammar the integrations share,
`<module>:<site>[/<detail>]`, so a SIEM rule keying on the scheme can tell a log4j2 finding from a JDBC
or a Kafka one without parsing the rest.

| Path | What it names |
|---|---|
| `log4j2:<logger>/event` | Masking the event as a whole failed |
| `log4j2:<logger>/message` | The message, its format, or the object an `ObjectMessage` carries |
| `log4j2:<logger>/message/<key>` | One entry of a map message |
| `log4j2:<logger>/arg<n>` | The n-th parameter of the logging call |
| `log4j2:<logger>/mdc/<key>` | One entry of the thread context map |
| `log4j2:<logger>/throwable` | The thrown exception, then `/cause` and `/suppressed/<n>` down its graph |

The site is the logger, which is what identifies the code that logged the line; the root logger, whose
name is the empty string, is written `<root>` so the site is never blank. The engine appends
object-graph members with a dot, so a declared field of a logged object is reported at
`log4j2:ch.example.PaymentService/arg0.iban` — the site says the value came out of a log parameter, the
tail says which field of it.

Every detector hit is reported to `MaskingObserver.onUnannotatedPii`, not `onScanned`: nothing a log
event carries was ever declared as free text to be scanned, so a hit here is a field carrying PII
nobody classified — the earliest warning that a log line has started leaking, and enough to find the
code that put it there.

## An event that carried nothing is forwarded as itself

The engine and the text sanitiser both return the **same instance** when nothing was masked, so
`LogEventMasker` compares references and hands back the original event. A PII-free log line pays for the
scan and nothing else — no copy, no allocation. That is what makes this affordable on a path that runs
on every line.

## Garbage-free logging is covered

With `log4j2.enableThreadlocals` on — the default everywhere except a web app — a logger does not build
a `ParameterizedMessage`. It reuses a `ReusableParameterizedMessage`, and what reaches a rewrite policy
or an appender is often a `MutableLogEvent` standing in for its own message. Both are masked exactly
like their immutable counterparts: the format is scanned and each parameter is masked from its `@PII`
declarations, so `logger.info("paid {}", customer)` is covered identically in both modes.

Materialization is the one difference. A reusable message is recycled the moment the logging call
returns, so a masked line **leaves the reusable lifecycle**: the copy is an immutable event carrying an
immutable message, safe to hold, buffer or ship asynchronously. A clean line stays in it — the same
reusable event is forwarded untouched, so the allocation-free path log4j2 promises is only paid for by
lines that actually carried something to mask.

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
original's frames copied onto it. A type with neither gets a stand-in that carries the original class
name in its **message**, so `%ex` reads exactly as it did and the message a JSON layout writes still
names what was thrown. What is lost is state the exception held in fields of its own — a SQL state, an
error code; no layout prints it, and losing it is the fail-closed direction.

The graph is bounded in both directions. A cause chain deeper than `maxDepth` is cut, reported through
`onDepthLimitExceeded` with the path of the cause it stopped at. A suppressed list longer than
`maxCollectionElements` is cut, reported through `onCollectionTruncated` with the path of the *list* and
the number of entries kept — a batch that fails item by item suppresses one exception per item, each
with a cause chain and a trace of its own, and walking all of them is how a log statement becomes the
outage. The two are separate signals because a deep exception graph and a long one want different
responses, and because one signal per dropped entry under a synthesised index is something nothing
downstream can group. Both drop rather than pass through, which discloses nothing.

**The one residual limitation is the stand-in's own class.** A `ThrowableProxy`, and every layout
derived from one, reads the class name off `getClass()`; the field is private and final, and there is no
API to set it. So for the rare type that has neither constructor, a `JsonTemplateLayout` writes
`ch.raph.datamask.log4j2.MaskedThrowables$MaskedThrowable` as `exception.class`, and
`exception.message` is where the original type is to be read — it is prefixed there, in the
`com.example.OddException: <masked message>` form `toString()` has always used. Anything that alerts or
groups on `exception.class` should treat that name as "the type below is in the message". Giving the
substitute the original's class name would mean generating a class per exception type at runtime, which
is not a trade this module makes on a logging path. Every exception with a `(String)` or
`(String, Throwable)` constructor — which is nearly all of them, including everything the JDK, Spring
and the JDBC drivers throw — is reconstructed as itself and is unaffected.

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

`DataMaskLog4j2` is a thin front on `InstalledDataMask`, and the plugins resolve through
`ResolvedMasker<LogEventMasker>` — both in `datamask-core`, shared with every other integration that
starts before the application does. What the shared type is really for is the caching rule: the derived
masker is keyed on the *identity* of the installed instance, which stays null while nothing is
installed, so the fallback is built once rather than per event and a late install rebuilds it the
moment it arrives. The static field also carries the caveat every such hand-off inherits — one instance
per classloader that loaded the holder, so in an application server each deployment installs its own,
and on a shared classpath the last install wins.

What stays log4j2's own is reading the `secret` off a plugin attribute and reporting through log4j2's
`StatusLogger` — never through the logger being masked, which would be the value's own way out.

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

76, all asserting the raw value is **absent** from what a layout renders rather than only that the
masked form is present. They cover the declared strategies, bare values a detector recognises, map and
object messages, the context map, cause chains and suppressed exceptions, the same-type reconstruction
and its stand-in — including that the stand-in names the original type both in its `toString()` and in
the message a layout derived from a `ThrowableProxy` reads — both policies, plugin registration through
the generated descriptor, the fail-closed paths, and garbage-free mode — reusable messages, a mutable
event standing in for its own message, and a real logger running with threadlocals enabled.

The observer paths are asserted site by site — message, parameter, map-message entry, context map,
thrown, cause, suppressed — plus that every path a rich event produces carries the `log4j2:` scheme,
that a root-logger event names `<root>` rather than nothing, that a cut cause chain reports a depth
limit and a cut suppressed list reports a truncation with the number kept, and that neither reports
the other's signal.
