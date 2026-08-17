package ch.raph.datamask.application;

import ch.raph.datamask.infrastructure.reflect.Types;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fits a masked value back into the slot it came from.
 *
 * <p>Masking naturally produces text, but the member it has to go back into may be a
 * {@code BigDecimal} balance or an {@code int} age. Rather than refuse to mask numeric PII, the
 * value is replaced by the type's zero — which discloses nothing and keeps the rebuilt object
 * type-correct.
 */
final class Coercion {

    private Coercion() {}

    static Object toDeclaredType(Object value, Class<?> declaredType) {
        if (declaredType == Object.class) {
            return value;
        }
        if (value == null) {
            return declaredType.isPrimitive() ? zeroOf(declaredType) : null;
        }

        Class<?> boxed = Types.box(declaredType);
        if (boxed.isInstance(value)) {
            return value;
        }
        if (boxed == StringBuilder.class) {
            return new StringBuilder(value.toString());
        }
        if (boxed == StringBuffer.class) {
            return new StringBuffer(value.toString());
        }
        if (CharSequence.class.isAssignableFrom(boxed)) {
            return boxed == String.class ? value.toString() : value;
        }
        // A masked collection is rebuilt to whatever shape the slot needs. Without this a Deque
        // member masked into an ArrayList — or any other shape mismatch — would fall through to
        // zeroOf and take the entire collection to null.
        if (value instanceof Collection<?> collection && Collection.class.isAssignableFrom(boxed)) {
            Collection<Object> fitted = collectionFor(boxed);
            if (fitted != null) {
                return fill(fitted, collection);
            }
        }
        if (value instanceof Map<?, ?> map && Map.class.isAssignableFrom(boxed)) {
            Map<Object, Object> fitted = mapFor(boxed);
            if (fitted != null) {
                return fill(fitted, map);
            }
        }
        return zeroOf(declaredType);
    }

    private static Collection<Object> collectionFor(Class<?> declaredType) {
        if (declaredType.isAssignableFrom(ArrayList.class)) {
            return new ArrayList<>();
        }
        if (declaredType.isAssignableFrom(LinkedHashSet.class)) {
            return new LinkedHashSet<>();
        }
        if (declaredType.isAssignableFrom(TreeSet.class)) {
            return new TreeSet<>();
        }
        if (declaredType.isAssignableFrom(ArrayDeque.class)) {
            return new ArrayDeque<>();
        }
        return null;
    }

    private static Map<Object, Object> mapFor(Class<?> declaredType) {
        if (declaredType.isAssignableFrom(LinkedHashMap.class)) {
            return new LinkedHashMap<>();
        }
        if (declaredType.isAssignableFrom(TreeMap.class)) {
            return new TreeMap<>();
        }
        if (declaredType.isAssignableFrom(ConcurrentHashMap.class)) {
            return new ConcurrentHashMap<>();
        }
        return null;
    }

    /**
     * Copies element by element, dropping what the target refuses — a null in an {@code ArrayDeque},
     * a masked value a comparator will not order. Refusing the whole collection instead would take
     * the field to null, which loses more than dropping one element does.
     */
    private static Object fill(Collection<Object> target, Collection<?> source) {
        for (Object element : source) {
            try {
                target.add(element);
            } catch (NullPointerException | ClassCastException | IllegalArgumentException refused) {
                // Dropped on purpose.
            }
        }
        return target;
    }

    private static Object fill(Map<Object, Object> target, Map<?, ?> source) {
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            try {
                target.put(entry.getKey(), entry.getValue());
            } catch (NullPointerException | ClassCastException | IllegalArgumentException refused) {
                // Dropped on purpose.
            }
        }
        return target;
    }

    private static Object zeroOf(Class<?> type) {
        Class<?> boxed = Types.box(type);
        if (boxed == Boolean.class) {
            return Boolean.FALSE;
        }
        if (boxed == Character.class) {
            return '*';
        }
        if (boxed == Byte.class) {
            return (byte) 0;
        }
        if (boxed == Short.class) {
            return (short) 0;
        }
        if (boxed == Integer.class) {
            return 0;
        }
        if (boxed == Long.class) {
            return 0L;
        }
        if (boxed == Float.class) {
            return 0f;
        }
        if (boxed == Double.class) {
            return 0d;
        }
        if (boxed == BigDecimal.class) {
            return BigDecimal.ZERO;
        }
        if (boxed == BigInteger.class) {
            return BigInteger.ZERO;
        }
        return null;
    }
}
