package ch.raph.datamask.logback;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import net.logstash.logback.marker.EmptyLogstashMarker;
import net.logstash.logback.marker.LogstashMarker;
import net.logstash.logback.marker.MapEntriesAppendingMarker;
import net.logstash.logback.marker.Markers;
import net.logstash.logback.marker.ObjectAppendingMarker;
import net.logstash.logback.marker.ObjectFieldsAppendingMarker;
import net.logstash.logback.marker.RawJsonAppendingMarker;
import net.logstash.logback.marker.SingleFieldAppendingMarker;
import org.slf4j.Marker;

/**
 * Rebuilds a logstash appending marker around its masked payload.
 *
 * <p>{@code Markers.append("customer", customer)} is how logstash-logback-encoder attaches a whole
 * object to a line, and the encoder serialises it into the shipped JSON — so the payload is masked
 * exactly as an argument would be, and the marker is reconstructed through the same {@code Markers}
 * factories the caller used. References are carried over masked, since {@code
 * Markers.aggregate(...)} chains markers through them.
 *
 * <p>The payloads are held in non-public members, so they are read reflectively; a payload that
 * cannot be read — the reflection blocked, a {@link net.logstash.logback.marker.DeferredLogstashMarker
 * DeferredLogstashMarker} whose value does not exist until encoding, a subclass this class has never
 * heard of — makes the whole marker unmaskable, and {@link MarkerMasker} replaces it with the
 * redaction placeholder. Never inspected means never forwarded.
 *
 * <p>Two deliberate losses, both in the fail-closed direction: a custom {@code
 * messageFormatPattern} is not carried into a rebuilt marker (it shapes {@code toString()} only,
 * never the JSON), and a raw-JSON payload that needed masking is re-attached as an ordinary string
 * field, because masking a fragment of raw JSON can leave it unparseable.
 *
 * <p>This class is loaded only after {@link MarkerMasker} has confirmed the encoder is on the
 * classpath, which is what keeps the dependency optional.
 */
final class LogstashMarkerMasker {

    private static final Method FIELD_VALUE = accessor();
    private static final Field MAP_ENTRIES = payloadField(MapEntriesAppendingMarker.class, "map");
    private static final Field OBJECT_FIELDS = payloadField(ObjectFieldsAppendingMarker.class, "object");

    private LogstashMarkerMasker() {}

    static boolean handles(Marker marker) {
        return marker instanceof LogstashMarker;
    }

    /** The field name where there is one — the name a failure report can be traced back to code by. */
    static String name(Marker marker) {
        return marker instanceof SingleFieldAppendingMarker single ? single.getFieldName() : marker.getName();
    }

    /** The same instance when payload and references were both clean, a masked replacement otherwise. */
    static Marker mask(Marker marker, MarkerMasker context, String path, int depth) {
        LogstashMarker original = (LogstashMarker) marker;
        MarkerMasker.Children children = context.maskChildren(original, path, depth);
        LogstashMarker head = remade(original, context, path, children.changed());
        if (head == original) {
            return original;
        }
        for (Marker child : children.markers()) {
            head.add(child);
        }
        return head;
    }

    /**
     * The marker rebuilt around its masked payload — childless, the caller re-attaches those — or
     * the same instance when the payload was clean and {@code force} is off. {@code force} asks for
     * a rebuilt copy even then, because a marker's references cannot be replaced in place.
     */
    private static LogstashMarker remade(LogstashMarker original, MarkerMasker context, String path, boolean force) {
        return switch (original) {
            case ObjectAppendingMarker m
            when m.getClass() == ObjectAppendingMarker.class -> {
                Object value = fieldValue(m);
                Object safe = context.maskValue(value, path);
                yield safe == value && !force ? m : Markers.append(m.getFieldName(), safe);
            }
            case RawJsonAppendingMarker m
            when m.getClass() == RawJsonAppendingMarker.class -> {
                String raw = fieldValue(m) instanceof String text ? text : null;
                String safe = context.scan(raw, path);
                if (safe == raw && !force) {
                    yield m;
                }
                // A masked fragment may no longer parse as JSON, so it travels as a string field.
                yield safe == raw ? Markers.appendRaw(m.getFieldName(), raw) : Markers.append(m.getFieldName(), safe);
            }
            case SingleFieldAppendingMarker m ->
                // A subclass this module does not know writes its value through code it cannot
                // inspect, so the marker is rebuilt around the declared value even when it came
                // back clean — what goes out is exactly what was examined.
                Markers.append(m.getFieldName(), context.maskValue(fieldValue(m), path));
            case MapEntriesAppendingMarker m
            when m.getClass() == MapEntriesAppendingMarker.class -> remadeEntries(m, context, path, force);
            case ObjectFieldsAppendingMarker m
            when m.getClass() == ObjectFieldsAppendingMarker.class -> {
                Object value = payload(OBJECT_FIELDS, m);
                Object safe = context.maskValue(value, path);
                yield safe == value && !force ? m : Markers.appendFields(safe);
            }
            case EmptyLogstashMarker m when m.getClass() == EmptyLogstashMarker.class -> force ? Markers.empty() : m;
            // DeferredLogstashMarker has no value until encoding, and an unknown subclass writes
            // JSON through code nothing here can inspect. Thrown rather than guessed at: the caller
            // reports it and substitutes the redaction placeholder.
            default ->
                throw new UnsupportedOperationException(
                        "marker type " + original.getClass().getName() + " carries a payload this module cannot read");
        };
    }

    private static LogstashMarker remadeEntries(
            MapEntriesAppendingMarker original, MarkerMasker context, String path, boolean force) {
        Map<?, ?> entries = (Map<?, ?>) payload(MAP_ENTRIES, original);
        if (entries == null || entries.isEmpty()) {
            return force ? Markers.appendEntries(Map.of()) : original;
        }
        Map<Object, Object> masked = null;
        for (Map.Entry<?, ?> entry : entries.entrySet()) {
            Object value = entry.getValue();
            // Keys are names a developer wrote, not data — same rule as MDC keys.
            Object safe = context.maskValue(value, path + "/" + entry.getKey());
            if (safe != value && masked == null) {
                masked = new LinkedHashMap<>(entries);
            }
            if (masked != null) {
                masked.put(entry.getKey(), safe);
            }
        }
        if (masked == null && !force) {
            return original;
        }
        return Markers.appendEntries(masked != null ? masked : entries);
    }

    private static Object fieldValue(SingleFieldAppendingMarker marker) {
        if (FIELD_VALUE == null) {
            throw new IllegalStateException(
                    "SingleFieldAppendingMarker.getFieldValue() is not accessible; the marker cannot be masked");
        }
        try {
            return FIELD_VALUE.invoke(marker);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "the payload of " + marker.getClass().getName() + " could not be read", e);
        }
    }

    private static Object payload(Field field, Object marker) {
        if (field == null) {
            throw new IllegalStateException(
                    "the payload field of " + marker.getClass().getName() + " is not accessible");
        }
        try {
            return field.get(marker);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "the payload of " + marker.getClass().getName() + " could not be read", e);
        }
    }

    /**
     * {@code getFieldValue()} is protected — the value is otherwise only reachable through the JSON
     * generator. Left null when opened reflection is refused, in which case every marker that needs
     * it is redacted rather than passed through.
     */
    private static Method accessor() {
        try {
            Method method = SingleFieldAppendingMarker.class.getDeclaredMethod("getFieldValue");
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException | RuntimeException refused) {
            return null;
        }
    }

    private static Field payloadField(Class<?> type, String name) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException refused) {
            return null;
        }
    }
}
