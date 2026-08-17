package ch.raph.datamask.kafka;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.serialization.Serializer;

/**
 * Reads this module's settings out of a client's configuration map — a producer's or a consumer's.
 *
 * <p>Each value is accepted both as the string a {@code .properties} file gives and as the real type
 * a map built in code gives, because a client is configured either way and a plugin sees whichever
 * the application used.
 */
final class Settings {

    private Settings() {}

    /** Defaults to false. Anything but a recognisable "true" is false, which is the safe direction. */
    static boolean maskKeys(Map<String, ?> configs) {
        return switch (configs.get(DataMaskKafka.MASK_KEYS_CONFIG)) {
            case null -> false;
            case Boolean flag -> flag;
            case CharSequence text -> Boolean.parseBoolean(text.toString().trim());
            default -> false;
        };
    }

    /** Lower-cased, so a lookup by header name can ignore case without normalising per record. */
    static Set<String> redactedHeaders(Map<String, ?> configs) {
        // Comma-separated as a string, matching Kafka's own list configs, whose namespace this shares.
        Collection<?> names =
                switch (configs.get(DataMaskKafka.REDACTED_HEADERS_CONFIG)) {
                    case null -> List.of();
                    case Collection<?> collection -> collection;
                    case CharSequence text -> Arrays.asList(text.toString().split(","));
                    default -> List.of();
                };

        Set<String> lowerCased = new LinkedHashSet<>();
        for (Object name : names) {
            if (name != null && !name.toString().isBlank()) {
                lowerCased.add(name.toString().trim().toLowerCase(Locale.ROOT));
            }
        }
        return Set.copyOf(lowerCased);
    }

    /**
     * The serializer {@link MaskingSerializer} was configured to delegate to. Returns null when the
     * key is absent, which is the caller's signal that the delegate has to have come from a
     * constructor instead. Configuring it is left to the caller, so that a delegate from here and a
     * delegate from a constructor are configured in exactly one place.
     */
    @SuppressWarnings("unchecked")
    static <T> Serializer<T> delegate(Map<String, ?> configs, boolean isKey) {
        String key = isKey ? DataMaskKafka.KEY_DELEGATE_CONFIG : DataMaskKafka.VALUE_DELEGATE_CONFIG;

        return switch (configs.get(key)) {
            case null -> null;
            // Already an instance: an application assembling the map in code, which is worth allowing
            // even though the same application could have used the constructor.
            case Serializer<?> instance -> (Serializer<T>) instance;
            case Class<?> type -> instantiate(type, key);
            case CharSequence name -> instantiate(load(name.toString().trim(), key), key);
            case Object other ->
                throw new KafkaException(key + " must be a Serializer, a Class or a class name, not "
                        + other.getClass().getName());
        };
    }

    private static Class<?> load(String name, String key) {
        // The context classloader first, because that is the one a container gave the application.
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        try {
            return Class.forName(name, true, loader != null ? loader : Settings.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new KafkaException(key + " names a class that is not on the classpath: " + name, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> Serializer<T> instantiate(Class<?> type, String key) {
        if (!Serializer.class.isAssignableFrom(type)) {
            throw new KafkaException(key + " must name a " + Serializer.class.getName() + ", and " + type.getName()
                    + " does not" + " implement it");
        }
        if (MaskingSerializer.class.isAssignableFrom(type)) {
            // Delegating to a masking serializer would mask twice, and with this same key configured
            // it would not terminate.
            throw new KafkaException(key + " must name the serializer to delegate to, not " + type.getName());
        }
        try {
            return (Serializer<T>) type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new KafkaException(
                    key + " names " + type.getName() + ", which has no usable no-argument constructor", e);
        }
    }
}
