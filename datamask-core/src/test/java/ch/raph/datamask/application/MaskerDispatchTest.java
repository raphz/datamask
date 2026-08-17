package ch.raph.datamask.application;

import static org.assertj.core.api.Assertions.assertThat;

import ch.raph.datamask.api.MaskContext;
import ch.raph.datamask.api.MaskStrategy;
import ch.raph.datamask.api.Masker;
import ch.raph.datamask.api.PII;
import ch.raph.datamask.domain.MaskingObserver;
import ch.raph.datamask.testdomain.Banking;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What a masker is asked about before it is used, and what a failure is attributed to.
 *
 * <p>{@code Masker.supports} used to receive the <em>declared</em> type of the member. A member
 * declared {@code Object} says nothing about what a masker would have to handle, and a masker
 * answering "no" is replaced by full redaction — so asking the wrong question quietly redacted values
 * that had a perfectly good masker waiting for them. The declared type still decides what the result
 * has to fit; it is just not the thing to ask a masker about.
 */
@DisplayName("Masker dispatch and failure attribution")
class MaskerDispatchTest {

    private static final String SECRET = "a-test-secret-of-sufficient-length";

    record LooselyTyped(
            @PII(strategy = MaskStrategy.DATE_GENERALIZE) Object birthDate) {}

    /** Answers only about the runtime type it really handles, so the engine's question is visible. */
    static final class LocalDateOnlyMasker implements Masker {
        final List<Class<?>> asked = new ArrayList<>();

        @Override
        public Object mask(Object value, MaskContext context) {
            return "masked-by-date-masker";
        }

        @Override
        public boolean supports(Class<?> type) {
            asked.add(type);
            return LocalDate.class.isAssignableFrom(type);
        }
    }

    @Nested
    @DisplayName("supports()")
    class Supports {

        @Test
        @DisplayName("is asked about the value's runtime class, not the type the member was declared as")
        void asksAboutTheRuntimeType() {
            LocalDateOnlyMasker masker = new LocalDateOnlyMasker();
            DataMask dataMask = DataMask.builder()
                    .secret(SECRET)
                    .masker(MaskStrategy.DATE_GENERALIZE, masker)
                    .build();

            dataMask.mask(new LooselyTyped(LocalDate.of(1985, 3, 14)));

            assertThat(masker.asked).containsExactly(LocalDate.class);
        }

        @Test
        @DisplayName("so a member declared Object still gets its real masker instead of blanket redaction")
        void looselyTypedMembersStillMaskProperly() {
            DataMask dataMask = DataMask.builder().secret(SECRET).build();

            LooselyTyped masked = dataMask.mask(new LooselyTyped(LocalDate.of(1985, 3, 14)));

            // DATE_GENERALIZE keeps the year and nothing else. Under the declared type the built-in
            // masker was asked about Object, and only answered yes because of a clause put there to
            // paper over exactly this.
            assertThat(masked.birthDate()).isEqualTo(LocalDate.of(1985, 1, 1));
        }

        @Test
        @DisplayName("still falls back to redaction when the masker genuinely cannot help, which is fail-closed")
        void unsupportedStillRedacts() {
            LocalDateOnlyMasker masker = new LocalDateOnlyMasker();
            DataMask dataMask = DataMask.builder()
                    .secret(SECRET)
                    .masker(MaskStrategy.DATE_GENERALIZE, masker)
                    .build();

            LooselyTyped masked = dataMask.mask(new LooselyTyped("not-a-date-at-all"));

            assertThat(masked.birthDate()).isNotEqualTo("not-a-date-at-all");
            assertThat(String.valueOf(masked.birthDate())).doesNotContain("not-a-date");
        }
    }

    @Nested
    @DisplayName("The root path")
    class RootPath {

        private List<String> failuresFrom(String rootPath) {
            List<String> failures = new ArrayList<>();
            DataMask dataMask = DataMask.builder()
                    .secret(SECRET)
                    .observer(new MaskingObserver() {
                        @Override
                        public void onFailure(String path, Throwable error) {
                            failures.add(path);
                        }
                    })
                    .build();

            // Three fields and no constructor that could put them back: the engine masks the email,
            // finds it cannot rebuild, and reports a structural failure at the root of the graph.
            Object value = new Banking.Unrebuildable(1, "john.doe@example.com");
            if (rootPath == null) {
                dataMask.engine().mask(value);
            } else {
                dataMask.engine().mask(value, rootPath);
            }
            return failures;
        }

        @Test
        @DisplayName("is the empty string by default, which is what an application calling mask() should see")
        void defaultsToEmpty() {
            assertThat(failuresFrom(null)).containsExactly("");
        }

        @Test
        @DisplayName("lets an integration name the site, so a rule keyed on the scheme can tell sources apart")
        void integrationsCanNameTheSite() {
            assertThat(failuresFrom("kafka:value/payments")).containsExactly("kafka:value/payments");
        }
    }
}
