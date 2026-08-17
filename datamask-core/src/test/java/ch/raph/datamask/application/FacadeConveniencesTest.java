package ch.raph.datamask.application;

import static org.assertj.core.api.Assertions.assertThat;

import ch.raph.datamask.api.PiiCategory;
import ch.raph.datamask.domain.MaskingObserver;
import ch.raph.datamask.domain.PiiDetector;
import ch.raph.datamask.domain.PiiFinding;
import ch.raph.datamask.infrastructure.detect.RegexDetector;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The things an application had to reach through {@code engine()} for, and the null behaviour it had
 * to discover by hitting it.
 *
 * <p>Masking a single value whose category the caller already knows — a header, a query parameter, a
 * value pulled out of a map — needed a hand-built eight-argument {@code PiiDescriptor} and a call to
 * {@code engine().maskDeclared}. That is a lot of surface to expose for the second most obvious thing
 * anyone wants from a masking library.
 */
@DisplayName("The facade covers what applications kept reaching past it for")
class FacadeConveniencesTest {

    private static final String IBAN = "CH9300762011623852957";
    private static final String SECRET = "a-test-secret-of-sufficient-length";

    private final DataMask dataMask = DataMask.builder().secret(SECRET).build();

    @Nested
    @DisplayName("Masking one value against a known category")
    class MaskValue {

        @Test
        @DisplayName("masks it the way the category says, with no descriptor to build")
        void masksByCategory() {
            Object masked = dataMask.maskValue("john.doe@example.com", PiiCategory.EMAIL);

            assertThat(String.valueOf(masked)).doesNotContain("john.doe").contains("@");
        }

        @Test
        @DisplayName("reports the path it was given, so the observer signal names the site")
        void reportsThePath() {
            List<String> masked = new ArrayList<>();
            DataMask observed = DataMask.builder()
                    .secret(SECRET)
                    .observer(new MaskingObserver() {
                        @Override
                        public void onMasked(String path, PiiCategory category, ch.raph.datamask.api.MaskStrategy s) {
                            masked.add(path);
                        }
                    })
                    .build();

            observed.maskValue(IBAN, PiiCategory.IBAN, "http:header/x-account");

            assertThat(masked).containsExactly("http:header/x-account");
        }

        @Test
        @DisplayName("leaves null alone rather than inventing a placeholder for a value that was not there")
        void nullStaysNull() {
            assertThat(dataMask.maskValue(null, PiiCategory.EMAIL)).isNull();
        }
    }

    @Nested
    @DisplayName("Null handling")
    class Nulls {

        @Test
        @DisplayName("is the same across the facade: scan(null) is empty, not an exception")
        void scanOfNullIsEmpty() {
            // maskText(null) already returned null while scan(null) threw, which is the kind of
            // inconsistency an application discovers in production from an auditing call.
            assertThat(dataMask.scan(null)).isEmpty();
            assertThat(dataMask.scan("")).isEmpty();
            assertThat(dataMask.maskText(null)).isNull();
        }
    }

    @Nested
    @DisplayName("Text masking with a path")
    class TextPaths {

        @Test
        @DisplayName("attributes the finding to the caller's site instead of the literal \"text\"")
        void usesTheGivenPath() {
            List<String> paths = new ArrayList<>();
            DataMask observed = DataMask.builder()
                    .secret(SECRET)
                    .observer(new MaskingObserver() {
                        @Override
                        public void onUnannotatedPii(String path, PiiCategory category, String detector) {
                            paths.add(path);
                        }
                    })
                    .build();

            observed.maskText("payment for " + IBAN, "http:body/note");

            assertThat(paths).containsExactly("http:body/note");
        }
    }

    @Nested
    @DisplayName("Detector priority")
    class DetectorPriority {

        /** Claims the whole reference, which the built-in IBAN detector would otherwise take. */
        private PiiDetector houseReference() {
            return new RegexDetector(
                    "house-reference", PiiCategory.CUSTOMER_ID, Pattern.compile("CH\\d{19}", Pattern.CASE_INSENSITIVE));
        }

        @Test
        @DisplayName("appending leaves a built-in detector winning the tie, which is the documented order")
        void appendedDetectorLoses() {
            DataMask appended =
                    DataMask.builder().secret(SECRET).detector(houseReference()).build();

            List<PiiFinding> findings = appended.scan(IBAN);

            assertThat(findings).hasSize(1);
            assertThat(findings.getFirst().detector()).isEqualTo("iban");
        }

        @Test
        @DisplayName("detectorFirst wins it, which is the only way an institution-specific format can")
        void firstDetectorWins() {
            DataMask first = DataMask.builder()
                    .secret(SECRET)
                    .detectorFirst(houseReference())
                    .build();

            List<PiiFinding> findings = first.scan(IBAN);

            assertThat(findings).hasSize(1);
            assertThat(findings.getFirst().detector()).isEqualTo("house-reference");
            assertThat(findings.getFirst().category()).isEqualTo(PiiCategory.CUSTOMER_ID);
        }

        @Test
        @DisplayName("keeps the built-ins either way, so one added detector does not replace the set")
        void builtInsSurvive() {
            DataMask first = DataMask.builder()
                    .secret(SECRET)
                    .detectorFirst(houseReference())
                    .build();

            assertThat(first.scan("write to john.doe@example.com")).isNotEmpty();
        }
    }
}
