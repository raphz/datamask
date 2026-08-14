package ch.raph.datamask.application;

import ch.raph.datamask.infrastructure.reflect.Types;
import java.math.BigDecimal;
import java.math.BigInteger;

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
        if (CharSequence.class.isAssignableFrom(boxed)) {
            return boxed == String.class ? value.toString() : value;
        }
        return zeroOf(declaredType);
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
