package ch.raph.datamask.infrastructure.reflect;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.URL;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalAmount;
import java.util.Collection;
import java.util.Currency;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

/** Decides what the engine may take apart and what it must treat as a single value. */
public final class Types {

    private static final Set<Class<?>> LEAF_TYPES = Set.of(
            String.class,
            Boolean.class,
            Byte.class,
            Character.class,
            Short.class,
            Integer.class,
            Long.class,
            Float.class,
            Double.class,
            BigDecimal.class,
            BigInteger.class,
            UUID.class,
            URI.class,
            URL.class,
            Locale.class,
            Currency.class,
            Date.class,
            Class.class,
            StringBuilder.class,
            StringBuffer.class);

    private Types() {}

    /**
     * Whether a value of this type is atomic as far as masking is concerned.
     *
     * <p>Anything under {@code java.} that is not a container is treated as a leaf: taking apart
     * JDK internals would be both pointless and, on a module-protected class, impossible.
     */
    public static boolean isLeaf(Class<?> type) {
        // Object and bare interfaces say nothing about what they hold at runtime, so the engine
        // must look at the actual instance rather than stop here.
        if (type == Object.class) {
            return false;
        }
        if (type.isPrimitive() || type.isEnum() || LEAF_TYPES.contains(type)) {
            return true;
        }
        if (isContainer(type)) {
            return false;
        }
        if (Temporal.class.isAssignableFrom(type)
                || TemporalAmount.class.isAssignableFrom(type)
                || Date.class.isAssignableFrom(type)
                || Number.class.isAssignableFrom(type)
                || CharSequence.class.isAssignableFrom(type)) {
            return true;
        }
        if (type.isInterface()) {
            return false;
        }
        String packageName = type.getName();
        return packageName.startsWith("java.")
                || packageName.startsWith("javax.")
                || packageName.startsWith("jdk.")
                || packageName.startsWith("sun.");
    }

    public static boolean isContainer(Class<?> type) {
        return type.isArray()
                || Collection.class.isAssignableFrom(type)
                || Map.class.isAssignableFrom(type)
                || Optional.class.equals(type)
                || OptionalInt.class.equals(type)
                || OptionalLong.class.equals(type)
                || OptionalDouble.class.equals(type);
    }

    /**
     * A record with exactly one {@code String} component — the shape almost every domain uses for
     * a wrapped identifier. Recognising it is what makes {@code @PII Email email} mask the address
     * inside rather than replacing the whole value object.
     */
    public static boolean isSingleStringValueObject(Class<?> type) {
        if (!type.isRecord()) {
            return false;
        }
        var components = type.getRecordComponents();
        return components.length == 1 && CharSequence.class.isAssignableFrom(components[0].getType());
    }

    /** Boxes a primitive class so masked values can be checked against a declared primitive type. */
    public static Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        return switch (type.getName()) {
            case "boolean" -> Boolean.class;
            case "byte" -> Byte.class;
            case "char" -> Character.class;
            case "short" -> Short.class;
            case "int" -> Integer.class;
            case "long" -> Long.class;
            case "float" -> Float.class;
            case "double" -> Double.class;
            default -> type;
        };
    }
}
