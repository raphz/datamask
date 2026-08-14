# datamask-kafka

**Keeps PII out of what a producer publishes — headers included.**

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
engine in. `DataMaskKafka.install(...)` is that hand-off, and it is looked up **per record** — a
producer built before the install still picks it up, from the next record on.

Until something is installed, masking runs under strict defaults and an ephemeral key. Everything is
masked; what an ephemeral key costs is that a `HASH` pseudonym differs between instances and after a
restart, so a pseudonymised customer id stops correlating across the topic. The fallback logs an ERROR
saying so.

An application that constructs its own plugins does not need any of this — pass a `DataMask` or a
`MaskingEngine` to the constructor. Spring's `DefaultKafkaProducerFactory` takes serializer instances,
so that is the route there.

## Consumers are untouched

Deliberately, and for the same reason `datamask-jackson` leaves deserialization alone: this module
protects what leaves the process. Masking on the way in would destroy data the application is meant to
process, and a record that was masked before it was published is already safe to read.

## Using it elsewhere

`RecordMasker` is the masking itself, for the same job somewhere this module does not reach — a Kafka
Streams processor, a Spring `ProducerListener`, a bridge forwarding records between clusters:

```java
RecordMasker masker = new RecordMasker(dataMask);
ProducerRecord<String, Payment> safe = masker.mask(record);
```

It returns the **same record** when there was nothing to mask, which is the common case and costs no
allocation.

## Observability

Every masked value is reported to the `MaskingObserver` with a path that names the site:
`kafka:value/payments`, `kafka:key/payments`, `kafka:header/payments/x-customer-email`.

`onUnannotatedPii` is the one to alert on. It fires when a detector finds PII in something nobody
annotated, and on this boundary that means a value nobody classified is on its way to a topic — the
earliest warning available that a new field is leaking.

## Tests

`MaskingProducerKafkaTest` produces to a real broker via Testcontainers, reads the record back as raw
bytes, and asserts on exactly what landed. Its first test asserts that the payload and the header
**do** leak without the module — a test that a value is absent proves nothing unless the same produce
demonstrably leaks it otherwise. It is skipped when Docker is unavailable; CI has Docker.

Everything else runs without a broker, by calling `onSend` and `serialize` directly, which is those
plugins' actual contract. `MockProducer` is not used and could not be: it stores the `ProducerRecord`
objects it was handed rather than the bytes, and it never runs the interceptor chain.
