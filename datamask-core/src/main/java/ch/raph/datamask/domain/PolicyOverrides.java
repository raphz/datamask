package ch.raph.datamask.domain;

import java.util.Map;
import java.util.Optional;

/**
 * Masking declared outside the code being masked.
 *
 * <p>Not every class can be annotated: DTOs generated from an OpenAPI contract or an Avro schema
 * are regenerated on every build, and third-party models cannot be touched at all. Without this,
 * the library would only protect the code that needs it least.
 *
 * <p>Keys are {@code fully.qualified.Type} for a whole type and {@code fully.qualified.Type#member}
 * for a single member, with the member entry taking precedence.
 */
public record PolicyOverrides(Map<String, PiiDescriptor> byMember, Map<String, PiiDescriptor> byType) {

    private static final PolicyOverrides NONE = new PolicyOverrides(Map.of(), Map.of());

    public PolicyOverrides {
        byMember = Map.copyOf(byMember);
        byType = Map.copyOf(byType);
    }

    public static PolicyOverrides none() {
        return NONE;
    }

    public Optional<PiiDescriptor> forMember(Class<?> owner, String memberName) {
        return Optional.ofNullable(byMember.get(owner.getName() + "#" + memberName));
    }

    public Optional<PiiDescriptor> forType(Class<?> type) {
        return Optional.ofNullable(byType.get(type.getName()));
    }

    public boolean isEmpty() {
        return byMember.isEmpty() && byType.isEmpty();
    }
}
