package ch.raph.datamask.domain;

import static org.assertj.core.api.Assertions.assertThat;

import ch.raph.datamask.api.NoMask;
import ch.raph.datamask.api.PII;
import ch.raph.datamask.api.PiiCategory;
import ch.raph.datamask.application.DataMask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Dropping a member outright, which is what an override asks for when masking a value is not enough
 * and it must not leave the process at all.
 *
 * <p>{@code MaskAction.Drop} sat in the sealed hierarchy with nothing able to produce it: no
 * {@code MaskStrategy.DROP}, nothing in the compiler, nothing in {@code PolicyOverrides}. A case in a
 * sealed interface that cannot occur is a claim the domain makes and cannot back — and the capability
 * behind it is real, because a serializer honouring a drop removes the property rather than writing
 * it as null, so not even the field's existence is disclosed.
 */
@DisplayName("An override can drop a member entirely")
class PolicyOverrideDropTest {

    record Customer(@PII(category = PiiCategory.EMAIL) String email, String country) {}

    record Exempted(
            @NoMask(justification = "an internal reference, not personal data")
            String reference) {}

    private static DataMask dropping(PolicyOverrides overrides) {
        return DataMask.builder()
                .secret("a-test-secret-of-sufficient-length")
                .overrides(overrides)
                .build();
    }

    @Nested
    @DisplayName("The drop")
    class Drop {

        @Test
        @DisplayName("leaves the member out of the masked copy rather than masking it")
        void dropsTheMember() {
            DataMask dataMask = dropping(
                    PolicyOverrides.builder().drop(Customer.class, "email").build());

            Customer masked = dataMask.mask(new Customer("john.doe@example.com", "CH"));

            assertThat(masked.email()).isNull();
            assertThat(masked.country()).isEqualTo("CH");
        }

        @Test
        @DisplayName("beats @NoMask, because the deployment is the one being audited and the annotation is a "
                + "claim by the code's author")
        void dropsBeatAnExemption() {
            DataMask dataMask = dropping(
                    PolicyOverrides.builder().drop(Exempted.class, "reference").build());

            assertThat(dataMask.mask(new Exempted("REF-8842")).reference()).isNull();
        }

        @Test
        @DisplayName("touches only the member it names")
        void leavesOtherMembersAlone() {
            DataMask dataMask = dropping(
                    PolicyOverrides.builder().drop(Customer.class, "country").build());

            Customer masked = dataMask.mask(new Customer("john.doe@example.com", "CH"));

            assertThat(masked.country()).isNull();
            assertThat(masked.email()).isNotNull().doesNotContain("john.doe");
        }
    }

    @Nested
    @DisplayName("The overrides themselves")
    class Overrides {

        @Test
        @DisplayName("count a drop as content, so the generated-plan compiler still steps aside")
        void aDropMakesOverridesNonEmpty() {
            PolicyOverrides overrides =
                    PolicyOverrides.builder().drop(Customer.class, "email").build();

            // isEmpty() is what DataMask.builder() consults to decide whether a compile-time plan may
            // answer. A plan generated before the override existed cannot know about the drop, so an
            // override that reported itself empty would be silently ignored.
            assertThat(overrides.isEmpty()).isFalse();
            assertThat(PolicyOverrides.none().isEmpty()).isTrue();
        }

        @Test
        @DisplayName("keep the two-argument constructor working, so declaring no drops needs no change")
        void theTwoArgumentConstructorStillWorks() {
            PolicyOverrides overrides = new PolicyOverrides(
                    java.util.Map.of(),
                    java.util.Map.of(Customer.class.getName(), PiiDescriptor.of(PiiCategory.EMAIL)));

            assertThat(overrides.dropped()).isEmpty();
            assertThat(overrides.isEmpty()).isFalse();
        }
    }
}
