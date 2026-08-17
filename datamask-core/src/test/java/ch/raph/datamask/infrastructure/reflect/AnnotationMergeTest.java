package ch.raph.datamask.infrastructure.reflect;

import static org.assertj.core.api.Assertions.assertThat;

import ch.raph.datamask.api.MaskStrategy;
import ch.raph.datamask.api.PII;
import ch.raph.datamask.api.PiiCategory;
import ch.raph.datamask.api.Sensitivity;
import ch.raph.datamask.application.DataMask;
import ch.raph.datamask.domain.MaskingPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a {@code @PII} on a member and a {@code @PII} on that member's type add up to.
 *
 * <p>The merge used to be all or nothing: a member annotation that stated anything at all replaced
 * the type's declaration outright, and a bare one was replaced by it. Both directions lost
 * information silently, and silently is the operative word — the value still came out masked, just
 * masked as the wrong thing or at the wrong threshold, which no test and no reviewer would notice.
 */
@DisplayName("Member and type annotations merge")
class AnnotationMergeTest {

    @PII(category = PiiCategory.EMAIL)
    record Email(String value) {}

    record KeepsTypeCategory(@PII(keep = 4) Email email) {}

    record OverridesSensitivity(
            @PII(sensitivity = Sensitivity.LOW) Email email) {}

    record OverridesStrategy(
            @PII(strategy = MaskStrategy.HASH) Email email) {}

    record Bare(@PII Email email) {}

    private final DataMask dataMask =
            DataMask.builder().secret("a-test-secret-of-sufficient-length").build();

    @Test
    @DisplayName("a member that states only `keep` still inherits the type's category")
    void memberKeepsTheTypeCategory() {
        KeepsTypeCategory masked = dataMask.mask(new KeepsTypeCategory(new Email("john.doe@example.com")));

        // EMAIL masking keeps the domain shape readable. Losing the category would have masked the
        // address as an anonymous string — still safe, but no longer diagnosable in a log.
        assertThat(masked.email().value())
                .doesNotContain("john.doe")
                .contains("@")
                .contains(".com");
    }

    @Test
    @DisplayName("a member that states only `strategy` still inherits the type's category")
    void memberKeepsTheTypeCategoryUnderAnExplicitStrategy() {
        OverridesStrategy masked = dataMask.mask(new OverridesStrategy(new Email("john.doe@example.com")));

        assertThat(masked.email().value()).doesNotContain("john.doe").startsWith("~");
    }

    @Test
    @DisplayName("a member's explicit sensitivity survives the type's declaration")
    void memberSensitivityWins() {
        DataMask highOnly = DataMask.builder()
                .secret("a-test-secret-of-sufficient-length")
                .policy(MaskingPolicy.strict().withThreshold(Sensitivity.HIGH))
                .build();

        // LOW is below the threshold, so the member is deliberately left readable. Discarding the
        // member's sensitivity would have masked it anyway — the dial exists to be obeyed.
        assertThat(highOnly.mask(new OverridesSensitivity(new Email("john.doe@example.com")))
                        .email()
                        .value())
                .isEqualTo("john.doe@example.com");
    }

    @Test
    @DisplayName("a bare member annotation still defers entirely to the type")
    void bareMemberDefersToTheType() {
        Bare masked = dataMask.mask(new Bare(new Email("john.doe@example.com")));

        assertThat(masked.email().value()).doesNotContain("john.doe").contains("@");
    }
}
