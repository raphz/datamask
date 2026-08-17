# datamask-kafka

**Keeps PII out of what a producer publishes — headers included — and, where a consumer asks for it,
out of what a poll hands the application.**

```java
DataMaskKafka.install(DataMask.builder().secret(System.getenv("DATAMASK_SECRET")).build());
```

```properties
interceptor.classes=ch.raph.datamask.kafka.MaskingProducerInterceptor
datamask.headers.redact=x-customer-ref,x-account
```

## The leak

A topic is a durable, replicated copy of whatever was written to it. It is retained for as long as the
topic says rather than as long as anyone intended, it is read by whoever subscribed — a list nobody
enumerated — and it is mirrored to a lake, a search index and an analytics cluster by pipelines that
were configured once. Nothing about any of that looks like a leak while it is happening, and none of
it can be undone: a record cannot be recalled from a consumer that already read it.

The headers are the half nobody revisits. A correlation header added for one afternoon's debugging
stays on every record afterwards, every framework that bridges topics copies it across, and it reaches
consumers that were never told it exists.

## Two ways in

| | `MaskingSerializer` | `MaskingProducerInterceptor` |
|---|---|---|
| Payload | masked | masked |
| Headers | **not covered** | masked |
| Key | when configured into the key slot | when `datamask.mask.keys` is on |
| Needs | the serializer to be swappable | nothing but `interceptor.classes` |

**Prefer the interceptor.** It covers headers, and it needs nothing from the serializers — which is
what makes it work with a schema-registry serde a platform team owns, or a producer the application
code does not construct.

**Reach for the serializer** when the interceptor is not an option, or when the payload is the whole
concern. It is the narrower hook: it sees the value on its way to the bytes and nothing else.

Both together is fine. Masking a masked value changes nothing — a placeholder and a pseudonym are
recognised by no detector — so the payload is simply masked twice.

Two more hooks sit beside them: `MaskingSerde` for a Kafka Streams topology, and
`MaskingConsumerInterceptor` for the other direction — off unless a consumer asks for it, and worth
reading [the consumer side](#the-consumer-side) before switching on.

### The interceptor

```properties
interceptor.classes=ch.raph.datamask.kafka.MaskingProducerInterceptor
```

It runs in `KafkaProducer.send`, before the key and value are serialized and before a partition is
assigned, so it masks the payload as an **object graph** — from what `@PII` declares on the model
rather than by searching the bytes afterwards.

### The serializer

Programmatically:

```java
Serializer<Payment> serializer = new MaskingSerializer<>(new JsonSerializer<>(), dataMask);
Producer<String, Payment> producer = new KafkaProducer<>(configs, new StringSerializer(), serializer);
```

Or entirely from configuration:

```properties
value.serializer=ch.raph.datamask.kafka.MaskingSerializer
datamask.value.serializer=org.apache.kafka.common.serialization.StringSerializer
```

`datamask.value.serializer` is what it delegates to once it has masked. There is no default: a
serializer with nothing to delegate to refuses to start, because the alternative default — pass the
object through — would publish it unmasked.

### The Streams serde

Kafka Streams asks for a `Serde` where a plain producer asks for a serializer, so a topology has
nowhere to put a `MaskingSerializer`. `MaskingSerde` is that place: the masking serializer on the
write side, the delegate's own deserializer on the read side.

```java
Serde<Payment> masked = new MaskingSerde<>(new JsonSerde<>(Payment.class), dataMask);

builder.stream("payments", Consumed.with(Serdes.String(), new JsonSerde<>(Payment.class)))
        .filter(this::isSettled)
        .to("settled-payments", Produced.with(Serdes.String(), masked));
```

Reading is a pass-through on purpose. A topology that masked its input would be masking the values its
own operators are about to work on — the join key, the amount, the field it branches on — and then
writing the result of arithmetic over placeholders. Where a stream really does handle records whose
PII it does not need, the consumer interceptor below says so once for the whole application instead of
field by field.

Name it on the slot whose records leave the process — `Produced.with`, `Materialized.with` — rather
than as `default.value.serde`, which would also mask every internal repartition topic and so mask
values the topology then rejoins on. In a `Materialized` store it writes masked and reads back masked;
that is the point rather than a gap, because a state store is a changelog topic with the same
retention and the same readers.

## Why the serializer does not touch headers

`Headers` has no in-place set. It has `add` and `remove`, so rewriting a value from inside a serializer
means removing the header and adding it back, which moves it to the end of the list and reorders what
the caller wrote. Kafka's own serializer contract says as much: *"it is considered best practice to not
delete or modify existing headers, but rather only add new ones."*

The interceptor has no such problem. It runs earlier, where the record can be rebuilt whole, so the
headers keep their order and their duplicate keys exactly as they were written.

## What happens to a header

| | Treatment | Why |
|---|---|---|
| named in `datamask.headers.redact` | replaced with the placeholder | The detectors catch what they can recognise. An opaque internal identifier — `x-customer-ref: 4711` — looks like any other short string, and naming it is the only way it gets masked. Worth walking through the headers a service sets, once. |
| valid UTF-8 | scanned, PII masked in place | `x-payment-ref: transfer to CH93…` becomes `transfer to CH93 **** …`. `traceparent` matches nothing and travels intact. |
| not valid UTF-8 | **left alone** | No detector can read it, and binary trace propagation and a framework's own binary metadata travel this way. Destroying them would be reported as a bug rather than as protection. A binary header carrying PII is out of reach here — mask the payload it was serialized from instead. |
| header *names* | never touched | They are identifiers a developer wrote, and rewriting one breaks every consumer that reads it by name. |

The decode is strict: a lenient one invents replacement characters and would turn binary into
plausible-looking text.

## Keys are left alone by default

Masking a key is not the same decision as masking a value, and doing it silently would be wrong. The
serialized key picks the partition and drives log compaction, so a masked key changes **where records
land** and **which records survive**.

Only a deterministic strategy keeps that safe. `HASH` and `TOKENIZE` map distinct keys to distinct
surrogates, so partitioning stays even and compaction still collapses the right records together.
`REDACT` maps every key to `****`: one partition, and a compacted topic that keeps only the last
record of the whole topic.

So:

```properties
datamask.mask.keys=true
```

is opt-in, and configuring `MaskingSerializer` as `key.serializer` is itself the same opt-in.

The consumer interceptor reads the same setting and defaults the same way, for a different reason:
partitioning is already decided by then, but application code deduplicates, groups and correlates by
key, and a masked key changes all three without saying so.

## What a masking failure does

Failures are rare by construction. A masker that throws is resolved by
`MaskingPolicy#failureMode`, which redacts by default, so what reaches this boundary is a payload the
engine could not rebuild at all — or `FailureMode.THROW`, set by someone who wanted the bug surfaced.

**A header** has a fail-closed answer, because a header value is just bytes: it becomes the
placeholder, the failure goes to `MaskingObserver.onFailure`, and the record is still sent. Failing a
send over a header would be the larger outage.

**A payload** has none — the placeholder is a string and the delegate expects a `T` — so nothing is
published:

- The **serializer** throws. The producer's `send` fails with it. That is the right way round for a
  topic: a failed send is retried or surfaced, while a published record is permanent and has already
  been read.
- The **interceptor** cannot throw. Kafka catches whatever `onSend` throws, logs it, and carries on
  with **the record it had before the interceptor ran** — the unmasked one. An interceptor reporting a
  failure by throwing would therefore publish exactly the value it failed to mask. So it logs at ERROR
  instead, naming the topic and the path but never the value, and drops the record by returning null.
  The producer then fails that `send` from inside itself, with a `NullPointerException` that reads
  poorly on its own; the log line beside it is what says what happened.

## Where the DataMask comes from

`value.serializer` and `interceptor.classes` are class *names*. Kafka instantiates them through their
no-argument constructor and hands them a map of configuration, so there is no argument to pass an
engine in. `DataMaskKafka.install(...)` is that hand-off, and it is looked up **per record** — a client
built before the install still picks it up, from the next record on. The same install serves the
consumer interceptor; there is one hand-off, not one per direction.

The hand-off and the per-record resolution are the core's `InstalledDataMask` and `ResolvedMasker`,
shared with the logging integrations rather than written again here — `DataMaskKafka` holds one holder
and hands out a `ResolvedMasker<RecordMasker>` that captures the `datamask.mask.keys` and
`datamask.headers.redact` settings the plugin read out of its client's configuration. `InstalledDataMask`
is also where the caveat lives: a static field is shared by everything that shares its class, so in an
application server where each deployment has its own classloader each installs its own, and where the
library sits on a common classpath they share one and the last install wins.

Until something is installed, masking runs under strict defaults and an ephemeral key. Everything is
masked; what an ephemeral key costs is that a `HASH` pseudonym differs between instances and after a
restart, so a pseudonymised customer id stops correlating across the topic. The fallback logs an ERROR
saying so — once, not per record: the resolved masker is cached against the *identity* of the installed
instance, which stays absent while nothing is installed and changes the moment something is.

An application that constructs its own plugins does not need any of this — pass a `DataMask` or a
`MaskingEngine` to the constructor. Spring's `DefaultKafkaProducerFactory` takes serializer instances,
so that is the route there.

## The consumer side

```properties
interceptor.classes=ch.raph.datamask.kafka.MaskingConsumerInterceptor
datamask.headers.redact=x-customer-ref,x-account
```

`MaskingConsumerInterceptor` masks a record — value and headers — between the poll and the
application, using the same `RecordMasker` as the producer side. The value is already deserialized by
the time it runs, so it is the same object-graph masking, from what `@PII` declares. Under Spring Boot
it is one property, and the auto-configuration's `DataMaskKafka.install(...)` already covers the rest:

```properties
spring.kafka.consumer.properties.interceptor.classes=ch.raph.datamask.kafka.MaskingConsumerInterceptor
```

**It is off unless a consumer asks for it, and that is not a formality.** A consumer usually polls a
topic because it needs what is in it, and masking on the way in destroys exactly that. Switch it on
for the consumer whose job does not involve the PII: an audit trail, a projection that counts and
routes, a bridge copying records somewhere with a wider audience, a reader of a topic another team
fills.

Two things make it worth having even so.

**A topic has history.** Producer-side masking covers what is written after it is installed. Everything
already on the topic — and everything a producer nobody controls still writes — is raw, and this is the
only place a consuming application can do anything about it.

**The framework logs the record when the listener throws.** `ConsumerRecord.toString()` is

```
ConsumerRecord(topic = payments, partition = 0, ..., headers = ..., key = ..., value = ...)
```

— headers, key and value included. Spring Kafka's `DefaultErrorHandler` and `SeekUtils` log exactly
that, at ERROR and WARN, on every listener failure and on every retry that did not recover. The
payload a listener choked on therefore lands in the application log, at levels that ship everywhere,
written by code the application never sees. With this interceptor installed, the record they log is
the masked one, because it was masked before the listener was ever called.

### Dead-letter topics

A DLT is a topic, and it is written by a producer — so it gets the producer answer.
`DeadLetterPublishingRecoverer` republishes through a `KafkaTemplate`, so putting
`MaskingProducerInterceptor` on *that* producer factory masks the republished value, and masks the
`kafka_dlt-exception-message` and `kafka_dlt-exception-stacktrace` headers as well: both are UTF-8
text, so they are scanned like any other header. That matters more than it looks — a stack trace
quotes the value that caused the failure (`NumberFormatException: For input string: "…"`), and it is
the one part of a DLT record nobody thinks of as a payload.

The DLT is also where the consumer interceptor pays off twice: a record that reaches the recoverer has
already been through it, so the value republished is the masked one even before the DLT producer looks
at it.

### What the consumer side does not reach

**A payload that failed to deserialize.** With Spring's `ErrorHandlingDeserializer` the original bytes
travel in a header as a serialized `DeserializationException`. Interceptors run after deserialization,
and that header is neither text nor an object graph, so nothing here can read it — see the binary
header row above. Mask it where it was written, on the producer, or strip that header before the DLT
publisher copies it.

**Anything logged before the poll returns.** A broker-level or deserializer-level failure that the
consumer logs on its own path never becomes a `ConsumerRecord` this interceptor is handed.

### What a record that cannot be masked does

It is dropped from the batch: the application never sees it, an ERROR names the topic, the partition
and the offset but never the value, and the failure is reported to `MaskingObserver.onFailure`.

Kafka catches whatever `onConsume` throws and carries on with **the records it had before the
interceptor ran** — the unmasked ones — so reporting the failure by throwing would deliver the very
value it failed to mask. Dropping means that record is skipped for good: nothing holds the offset
back, and the consumer commits past it like any other. That is data loss, and it is the deliberate
choice, because the only alternative on this path is handing the application what this interceptor was
installed to remove. Failures are rare by construction — the engine redacts a masker that throws — so
what reaches this path is a payload the engine could not rebuild at all, and the ERROR line is what
turns one into a bug report.

## Using it elsewhere

`RecordMasker` is the masking itself, for the same job somewhere this module does not reach — a Kafka
Streams processor, a Spring `ProducerListener`, a bridge forwarding records between clusters:

```java
RecordMasker masker = new RecordMasker(dataMask);
ProducerRecord<String, Payment> safe = masker.mask(record);
ConsumerRecord<String, Payment> safeIn = masker.mask(polled);
```

It returns the **same record** when there was nothing to mask, which is the common case and costs no
allocation. The `ConsumerRecord` form carries the position across untouched — partition, offset,
timestamp, leader epoch, delivery count and the serialized sizes, which describe the bytes that were
received and stay true of them.

## Observability

Every masked value is reported to the `MaskingObserver` with a path that names the site, in the
`<module>:<site>[/<detail>]` grammar the other integrations are aligned to:

```
kafka:value/payments                     the record value
kafka:key/payments                       the record key
kafka:header/payments/x-customer-email   one header of one topic
kafka:record/payments                    a whole record that had to be dropped
```

That path is also handed to the engine as the **root** of the graph it walks, so a failure at the root
of a payload — an unrebuildable type — is reported as `kafka:value/payments` rather than against the
empty string, and everything the walk reports below it (`kafka:value/payments.iban`) is built from it.
A rule that keys on the scheme therefore sees every event this module causes.

`onUnannotatedPii` is the one to alert on. It fires when a detector finds PII in something nobody
annotated, and on this boundary that means a value nobody classified is on its way to a topic — the
earliest warning available that a new field is leaking. Headers and plain-text payloads are scanned as
*undeclared* text for exactly that reason: nothing declared them free text, so a hit in one is the
signal, not routine. `onScanned` — the quieter event, for text a `@PII(strategy = SCAN)` or a
`FREEFORM_TEXT` category declared — therefore has no call site here, and giving it one would silence
the signal this boundary exists to raise.

`onCollectionTruncated` and `onDepthLimitExceeded` likewise have no call site of their own here. They
belong to the engine walking a payload, and they already arrive under this module's path because the
walk starts from it. Nothing in this module truncates anything: a record an interceptor cannot mask is
a failure rather than a size limit, and it is reported as one.

`onFailure` is reported with `kafka:record/payments` when an interceptor drops a whole record, on
either side. It names the record rather than a field because the record is what was lost, and it is
what puts a dropped record into `datamask.failures` instead of only into a log file nobody has an
alert on. A dropped payload therefore produces two reports — where the value broke, then the record
that was lost — and both are wanted.

## Tests

`MaskingKafkaTest` produces to a real broker via Testcontainers, reads the record back as raw bytes,
and asserts on exactly what landed. Its first test asserts that the payload and the header **do** leak
without the module — a test that a value is absent proves nothing unless the same produce demonstrably
leaks it otherwise. Its last one consumes that same unmasked topic through the consumer interceptor,
which is both the end-to-end proof for that side and the case it exists for. It is skipped when Docker
is unavailable; CI has Docker.

Everything else runs without a broker, by calling `onSend`, `onConsume` and `serialize` directly,
which is those plugins' actual contract. `MockProducer` is not used and could not be: it stores the
`ProducerRecord` objects it was handed rather than the bytes, and it never runs the interceptor chain.
