/**
 * Keeps PII out of what a producer publishes.
 *
 * <p>A topic is the worst place for a customer's data to land, and the easiest to overlook. It is a
 * durable, replicated copy of whatever was written to it, retained for as long as the topic says
 * rather than as long as anyone intended, and read by whoever subscribed — a list nobody enumerated.
 * Nothing about it looks like a leak while it is happening.
 *
 * <p>{@link ch.raph.datamask.kafka.MaskingSerializer} masks the payload on its way to the bytes, for a
 * producer whose serializer the application controls.
 * {@link ch.raph.datamask.kafka.MaskingProducerInterceptor} masks the whole record earlier, headers
 * included, for a producer whose serializer it does not — and headers are the part worth the module,
 * because a header set once for one debugging session then travels on every record afterwards.
 *
 * <p>{@link ch.raph.datamask.kafka.RecordMasker} is the masking itself, for anywhere else the same job
 * comes up. {@link ch.raph.datamask.kafka.DataMaskKafka} is where a plugin Kafka built from a class
 * name finds the {@code DataMask} it should use.
 */
package ch.raph.datamask.kafka;
