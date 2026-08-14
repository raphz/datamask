package ch.raph.datamask.spring;

import ch.raph.datamask.application.DataMask;

/**
 * The seam for anything the properties do not describe.
 *
 * {@snippet :
 * @Bean
 * DataMaskBuilderCustomizer kmsKey(KeyClient kms) {
 *     return builder -> builder.key(MaskKey.of(kms.dataKey("datamask")));
 * }
 * }
 *
 * <p>Every customizer runs after the auto-configuration has applied the properties and the beans it
 * found, and in {@code @Order} order, so the last word belongs to the application rather than to
 * this library. Replacing the {@code DataMask} bean outright is the other option; this one keeps
 * the property binding, the observers and the integration wiring.
 *
 * <p>Deriving the key from a KMS or a vault is the reason this exists: a key that has to be fetched
 * cannot be a property, and the alternative — a {@code datamask.secret} placeholder resolved by an
 * external property source — puts the key material through the environment, where {@code /env} and
 * a heap dump can both see it.
 */
@FunctionalInterface
public interface DataMaskBuilderCustomizer {

    void customize(DataMask.Builder builder);
}
