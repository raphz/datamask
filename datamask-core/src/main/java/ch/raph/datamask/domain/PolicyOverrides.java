package ch.raph.datamask.domain;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Masking declared outside the code being masked.
 *
 * <p>Not every class can be annotated: DTOs generated from an OpenAPI contract or an Avro schema
 * are regenerated on every build, and third-party models cannot be touched at all. Without this,
 * the library would only protect the code that needs it least.
 *
 * <p>Keys are {@code fully.qualified.Type} for a whole type and {@code fully.qualified.Type#member}
 * for a single member, with the member entry taking precedence.
 *
 * <p>Prefer {@link #builder()} to the canonical constructor: three collections in positional order,
 * two of them the same type, is a transposition waiting to happen.
 *
 * @param byMember masking for one member, keyed {@code Type#member}
 * @param byType   masking for every member whose declared type this names, keyed {@code Type}
 * @param dropped  members to omit from the masked copy entirely, keyed {@code Type#member}
 */
public record PolicyOverrides(
        Map<String, PiiDescriptor> byMember, Map<String, PiiDescriptor> byType, Set<String> dropped) {

    private static final PolicyOverrides NONE = new PolicyOverrides(Map.of(), Map.of(), Set.of());

    public PolicyOverrides {
        byMember = Map.copyOf(byMember);
        byType = Map.copyOf(byType);
        dropped = Set.copyOf(dropped);
    }

    /** Overrides that only redeclare masking, dropping nothing. */
    public PolicyOverrides(Map<String, PiiDescriptor> byMember, Map<String, PiiDescriptor> byType) {
        this(byMember, byType, Set.of());
    }

    public static PolicyOverrides none() {
        return NONE;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Optional<PiiDescriptor> forMember(Class<?> owner, String memberName) {
        return Optional.ofNullable(byMember.get(key(owner, memberName)));
    }

    public Optional<PiiDescriptor> forType(Class<?> type) {
        return Optional.ofNullable(byType.get(type.getName()));
    }

    /**
     * Whether this member is to be left out of the masked copy rather than masked into it.
     *
     * <p>A dropped member is not the same as one masked to a placeholder. A record rebuilds with
     * {@code null} in its place either way, but a serializer omits the property outright — which is
     * what a deployment means when it says a field must never leave the process at all, not even as
     * evidence that the field exists.
     */
    public boolean drops(Class<?> owner, String memberName) {
        return dropped.contains(key(owner, memberName));
    }

    public boolean isEmpty() {
        return byMember.isEmpty() && byType.isEmpty() && dropped.isEmpty();
    }

    private static String key(Class<?> owner, String memberName) {
        return owner.getName() + "#" + memberName;
    }

    /** Accumulates overrides without anyone having to remember which collection comes first. */
    public static final class Builder {

        private final Map<String, PiiDescriptor> byMember = new LinkedHashMap<>();
        private final Map<String, PiiDescriptor> byType = new LinkedHashMap<>();
        private final Set<String> dropped = new LinkedHashSet<>();

        private Builder() {}

        public Builder member(Class<?> owner, String memberName, PiiDescriptor descriptor) {
            byMember.put(key(owner, memberName), descriptor);
            return this;
        }

        public Builder type(Class<?> type, PiiDescriptor descriptor) {
            byType.put(type.getName(), descriptor);
            return this;
        }

        /** Omit this member from the masked copy entirely. See {@link PolicyOverrides#drops}. */
        public Builder drop(Class<?> owner, String memberName) {
            dropped.add(key(owner, memberName));
            return this;
        }

        public PolicyOverrides build() {
            return new PolicyOverrides(byMember, byType, dropped);
        }
    }
}
