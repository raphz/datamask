# datamask-logback

**Masks the logging event before an appender ever sees it, so PII never reaches a file, a console or a
log shipper.**

```xml
<appender name="MASKED" class="ch.raph.datamask.logback.MaskingAppender">
    <appender-ref ref="CONSOLE"/>
    <appender-ref ref="FILE"/>
</appender>

<root level="INFO">
    <appender-ref ref="MASKED"/>
</root>
```

Nothing else changes. The appenders keep their encoders, the domain model keeps its `@PII` annotations,
and no call site is touched.

Logs are the most common way PII escapes a banking system, and the reason this library exists. A log
line is also assembled from more places than a DTO is, and each of them leaks differently — which is why
all of them are handled here.

## What is masked

| Where | How | Why it matters |
|---|---|---|
| **Arguments** | Masked from their declarations, before formatting | `log.info("paid {}", customer)` masks the customer's IBAN because the field says what it is — and the rendered line never contained it |
| **The message** | Scanned by the detectors | `log.info("email " + email)` has no argument to mask and no annotation to read |
| **MDC values** | Values scanned, keys left alone | The quietest of the five: attached to every line of a request, by code nobody re-reads |
| **Key-value pairs** | Masked like arguments | `atInfo().addKeyValue("iban", iban)` is an argument by another name |
| **Exception messages** | Masked message down the cause chain and the suppressed list, frames untouched | `Key (email)=(john@x.com) already exists` is a constraint violation answering with the row that caused it |
| **Markers** | Logstash appending markers rebuilt around masked payloads; see below | `Markers.append("customer", customer)` is written into the JSON by the encoder, whatever the message says |

## The paths reported to the observer

Every path this module hands a `MaskingObserver` follows the grammar every DataMask integration
shares — `<module>:<site>[/<detail>]`. The scheme is the module name, so a SIEM rule keying on it can
tell a leak in a log line from one in a Kafka record or a JDBC parameter without parsing the rest:

| Site | Path | What it was |
|---|---|---|
| Message | `logback:com.acme.Payments/message` | the message template, scanned |
| Argument | `logback:com.acme.Payments/arg0` | the first argument |
| Field inside an argument | `logback:com.acme.Payments/arg0.iban` | a declared `@PII` member of it |
| MDC | `logback:com.acme.Payments/mdc/customerId` | one MDC entry, named by its key |
| Key-value pair | `logback:com.acme.Payments/kv/iban` | one pair from the fluent API |
| Exception | `logback:com.acme.Payments/throwable` | the exception message |
| Cause | `logback:com.acme.Payments/throwable/cause` | one level down the chain |
| Suppressed | `logback:com.acme.Payments/throwable/suppressed/0` | one entry of the suppressed list |
| Marker | `logback:com.acme.Payments/marker/customer` | a logstash appending marker, by field name |
| Nested marker | `logback:com.acme.Payments/marker/AUDIT/customer` | one hanging off a filtering marker |
| The event | `logback:com.acme.Payments/event` | reported only when masking the event failed |

Everything this module names itself is a slash-separated detail; the dot in `arg0.iban` comes from the
engine, which appends object-graph members that way. An argument is masked from that site rather than
from its own type, so a finding inside a logged object traces back to the call that logged it rather
than to a class name that appears in a hundred places.

`MaskingObserver.onUnannotatedPii` is what a detector hit reports here — not `onScanned`. Nothing this
module scans was declared as free text: a message, an MDC value and an exception message are all values
that simply arrived, so a hit in one is a field carrying PII nobody classified, which is the earliest
warning that a log line has started leaking and enough to find the code that put it there.

Two limits report separately, because they want different responses. A cause chain or a marker
reference graph that runs past `maxDepth` reports `onDepthLimitExceeded` with the path it stopped at —
a modelling surprise. A suppressed list longer than `maxCollectionElements` is cut and reports
`onCollectionTruncated("…/throwable/suppressed", kept)` against the container itself — a volume one,
usually a try-with-resources in a loop. The dropped tail never reaches the appenders.

## Markers are part of the payload, not just a label

In plain SLF4J a marker is a name used for filtering. In the most common JSON stack it is not:
logstash-logback-encoder attaches **whole objects** to a line with `Markers.append("customer", customer)`
and its encoder serialises them into the shipped JSON. An event whose message, arguments and MDC were
all masked would still ship everything such a marker carries.

So the marker list is treated exactly like the argument array:

- A **logstash appending marker** is rebuilt around its masked payload, through the same `Markers`
  factories the caller used. `append`, `appendEntries`, `appendFields`, `appendRaw` and aggregates are
  covered; references are walked, so a logstash marker attached as a child of a filtering marker is
  still found.
- A **plain SLF4J marker** — the kind every factory hands out for filtering — carries a name and
  nothing else, and passes through unchanged. Marker-based filters keep working.
- A marker of **any other type** may carry a payload nothing here can read, so it is replaced by a
  name-only marker with the same name: filtering on the name keeps working, the payload is stripped,
  and the strip is reported to the observer. Passing it through on the grounds that it is *probably*
  harmless would make an unknown marker type a way around masking.

logstash-logback-encoder is an **optional** dependency. Without it on the classpath this module masks
everything else exactly as before, and the encoder-specific class is never loaded.

Two deliberate losses inside a rebuilt marker, both in the fail-closed direction: a custom
`messageFormatPattern` is not carried over (it shapes `toString()` only, never the JSON), and a raw-JSON
payload that needed masking is re-attached as an ordinary string field, because masking a fragment of
raw JSON can leave it unparseable.

## The masked line is formatted the way logback would have formatted it

Masking replaces the arguments, so the line has to be re-rendered — and it is re-rendered through
logback's own `MessageFormatter`, from the masked message and the masked argument array. That is what
makes a masked line read like the unmasked one would have: an `int[]` argument renders as `[1, 2, 3]`
rather than as its identity, and nested arrays render element by element, primitive and object alike.

One rule lives here rather than in the formatter, because `LoggingEvent` applies it itself: a trailing
throwable argument is left out of the rendering unless it also became the event's throwable proxy.
Whether the last argument is a throwable is read off the **original** array — masking rewrites a
throwable whose message carried a value into text, and which argument is a throwable must not depend on
whether it needed masking, or every placeholder after it would shift.

One case still reads differently from the unmasked line: an **array that points back at itself**.
`MessageFormatter` prints such an array as `[…]`, but the masking engine copies an array by walking it
and stops on depth rather than on identity, so what reaches the formatter is a tree rather than a cycle
and it renders unrolled to `maxDepth`. Nothing is disclosed by it, and an array whose walk cannot be
completed at all leaves the line withheld rather than raw — but the fidelity is lost until the engine
detects cycles in arrays and collections the way it already does in objects.

## An event that carried nothing is forwarded as itself

The engine and the text sanitiser both return the **same instance** when nothing was masked, so
`LoggingEventMasker` compares references and hands back the original event — markers included. A
PII-free log line pays for the scan and nothing else: no copy, no allocation. That is what makes this
affordable on a path that runs on every line.

## Put the masking appender in front of the async one

Masking runs the detectors over the message, every MDC value and every exception message in the chain,
which is real work on the caller's thread. Wrapping it in logback's `AsyncAppender` moves that cost off
the request thread:

```xml
<appender name="ASYNC" class="ch.qos.logback.classic.AsyncAppender">
    <appender-ref ref="MASKED"/>
</appender>
```

Note the ordering constraint that comes with it: `AsyncAppender` calls
`prepareForDeferredProcessing()` and then hands the event to another thread. `MaskedLoggingEvent`
computes everything up front and every getter is a field read, so a masked event is safe to buffer and
ship — but an event queued *before* masking would be masked on the async thread, where an MDC lookup no
longer sees the request's context. Mask first, queue second.

## Where the DataMask comes from

`MaskingAppender` takes a `secret` in the configuration, or an instance installed in code:

```java
DataMaskLogback.install(DataMask.builder().secret(System.getenv("DATAMASK_SECRET")).build());
```

The install point exists because of when logging starts: a logback configuration is read before an
application has a container, a context or any beans, so an appender cannot be handed anything at that
point. It looks instead, per event, for something installed since — which is why an instance installed
after the first log line is still picked up.

`DataMaskLogback` is a thin naming of the core's `InstalledDataMask`, and the appender resolves through
the core's `ResolvedMasker` — the same hand-off the log4j2 and Kafka integrations use, so the caveat
that a static field is shared only within the classloader that loaded it is documented once, on
`InstalledDataMask`, rather than three times. `ResolvedMasker` is also what keys the resolution on the
installed instance, which is what makes a late install take effect while keeping the fallback built
once instead of per log line.

The fallback is safe rather than convenient: strict masking under an ephemeral key, reported once
through logback's own status manager — never through the logger being masked. Everything is masked;
what an ephemeral key costs is that a `HASH` pseudonym differs after a restart, which removes the
reason to prefer it over `REDACT`.

## Failing closed

A logging call must not fail the business operation, and must not fall back to text it could not mask.
When masking itself fails — including under `FailureMode.THROW` — the event keeps its level, logger,
thread and timestamp, and its message becomes `**** [datamask withheld this message: masking failed]`.
Its markers go too, because an encoder writes their payloads whatever the message says. The notice names
no exception message, because the exception was raised while handling a value and may well quote it.

## Tests

67 across the masker and the appender, all asserting the raw value is **absent** from what an encoder
renders rather than only that the masked form is present. They cover the declared strategies, bare
values a detector recognises, the MDC, key-value pairs, cause chains and suppressed exceptions, the
same-instance short-circuit, the observer paths site by site against the grammar above, the depth and
truncation signals reported apart from one another, the fail-closed paths, the hand-off (a late
install, a fallback built once rather than per event), the rendering of the masked line against what
logback itself renders for the same event (primitive and nested arrays, a self-referential one, the
trailing-throwable rule), and markers — an appended object and an appended map asserted against real
`LogstashEncoder` output, a logstash marker nested under a filtering one, a plain marker passing
through untouched, and an unknown marker type stripped to its name.
