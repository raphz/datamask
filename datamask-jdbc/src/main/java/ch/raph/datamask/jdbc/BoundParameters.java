package ch.raph.datamask.jdbc;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

/**
 * The parameters bound to one statement, already masked, in the order they were bound.
 *
 * <p>Nothing raw is ever stored here. A value is masked as it is recorded rather than when the line
 * is written, so this object cannot become a place PII sits waiting to be logged by something else.
 */
final class BoundParameters {

    private final Map<String, String> masked = new LinkedHashMap<>();

    void record(String parameter, String maskedValue) {
        masked.put(parameter, maskedValue);
    }

    void clear() {
        masked.clear();
    }

    boolean isEmpty() {
        return masked.isEmpty();
    }

    /** Renders as {@code [1=****, 2=<Integer>]} — call order, which for indexed binding is index order. */
    String describe() {
        StringJoiner joiner = new StringJoiner(", ", "[", "]");
        masked.forEach((parameter, value) -> joiner.add(parameter + "=" + value));
        return joiner.toString();
    }
}
