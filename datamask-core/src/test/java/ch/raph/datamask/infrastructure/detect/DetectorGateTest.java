package ch.raph.datamask.infrastructure.detect;

import static org.assertj.core.api.Assertions.assertThat;

import ch.raph.datamask.application.DataMask;
import ch.raph.datamask.domain.PiiDetector;
import ch.raph.datamask.domain.TextSignals;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The gates, held against the values they must never exclude.
 *
 * <p>A detector gate is the one thing in this library that can be wrong without anything going red.
 * The pattern still works, the checksum still holds, every test of the detector still passes — and
 * the detector is simply never asked, so the value is never masked. There is no exception, no
 * failure signal and no difference in the output except the presence of somebody's card number.
 *
 * <p>So each positive fixture here is checked three ways: the detector finds it (the fixture is
 * genuinely positive and has not rotted), the gate admits it (the fixture reaches the detector at
 * all), and the whole engine removes it from the text (the two agree end to end). The third is the
 * one that would catch a gate and a pattern that drifted apart.
 */
@DisplayName("Detector gates never exclude what their detector would have found")
class DetectorGateTest {

    private final DataMask dataMask =
            DataMask.builder().secret("gate-test-secret-not-a-real-key").build();

    /** A value each detector must find, in prose and — where it is one — as a bare value. */
    record Positive(String detector, Supplier<PiiDetector> factory, String text) {
        @Override
        public String toString() {
            return detector + ": " + text;
        }
    }

    static List<Positive> positives() {
        return List.of(
                new Positive("private-key", Detectors::privateKey, PEM),
                new Positive("jwt", Detectors::jsonWebToken, "token eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.c2ln"),
                new Positive("jwt", Detectors::jsonWebToken, "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.c2ln"),
                new Positive("bearer-token", Detectors::bearerToken, "Authorization: Bearer abcdef1234567890"),
                new Positive("bearer-token", Detectors::bearerToken, "header basic dXNlcjpwYXNzd29yZA=="),
                new Positive("assigned-secret", Detectors::assignedSecret, "config password=hunter2xyz loaded"),
                // Without the quote after the keyword: `"apiKey": "…"` is *not* matched by the
                // pattern today, though the class Javadoc claims it is. Noted in the improvements
                // log rather than fixed here — changing what a detector matches is a change to what
                // gets masked, and it does not belong in a performance batch.
                new Positive("assigned-secret", Detectors::assignedSecret, "apiKey: \"abcd1234efgh\""),
                new Positive("swiss-ahv", Detectors::swissAhv, "AVS 756.9217.0769.85 on file"),
                new Positive("swiss-ahv", Detectors::swissAhv, "756.9217.0769.85"),
                new Positive("iban", Detectors::iban, "transfer from CH9300762011623852957 received"),
                new Positive("iban", Detectors::iban, "CH9300762011623852957"),
                new Positive("iban", Detectors::iban, "transfer from CH93 0076 2011 6238 5295 7 received"),
                new Positive("iban", Detectors::iban, "CH93 0076 2011 6238 5295 7"),
                new Positive("iban", Detectors::iban, "account BE68 5390 0754 7034 opened"),
                new Positive("payment-card", Detectors::paymentCard, "charged card 4111111111111111 today"),
                new Positive("payment-card", Detectors::paymentCard, "4111111111111111"),
                new Positive("payment-card", Detectors::paymentCard, "charged 4111 1111 1111 1111 today"),
                new Positive("payment-card", Detectors::paymentCard, "charged 4111-1111-1111-1111 today"),
                // Thirteen digits with no separators: the shortest card the pattern accepts, and the
                // case the twelve-digit gate is closest to excluding.
                new Positive("payment-card", Detectors::paymentCard, "card 4222222222222 declined"),
                new Positive("email", Detectors::email, "wrote to john.doe@example.com yesterday"),
                new Positive("email", Detectors::email, "john.doe@example.com"),
                new Positive("phone-e164", Detectors::internationalPhone, "called +41 79 123 45 67 twice"),
                new Positive("phone-e164", Detectors::internationalPhone, "+41791234567"),
                new Positive("ipv4", Detectors::ipv4, "from 192.168.1.10 at noon"),
                new Positive("ipv4", Detectors::ipv4, "192.168.1.10"),
                new Positive("ipv6", Detectors::ipv6, "connect to 2001:db8::1 refused"),
                new Positive("ipv6", Detectors::ipv6, "2001:db8:85a3:8d3:1319:8a2e:370:7348"),
                new Positive("bic", Detectors::bic, "route via UBSWCHZH80A today"),
                new Positive("bic", Detectors::bic, "DEUTDEFFXXX"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("positives")
    @DisplayName("the detector finds it, so the fixture is a real positive")
    void fixtureIsPositive(Positive positive) {
        assertThat(positive.factory().get().detect(positive.text())).isNotEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("positives")
    @DisplayName("the gate lets it through to the detector")
    void gateAdmitsIt(Positive positive) {
        assertThat(positive.factory().get().mightMatch(TextSignals.of(positive.text())))
                .isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("positives")
    @DisplayName("and the engine still takes the value out of the text")
    void engineStillMasksIt(Positive positive) {
        String masked = dataMask.maskText(positive.text());

        assertThat(masked).isNotEqualTo(positive.text());
    }

    @Nested
    @DisplayName("On text that carries nothing")
    class OnCleanText {

        /** A line of the shape this library sees most: prose, no identifiers. */
        private static final String CLEAN = "settlement batch accepted by the clearing gateway, retry window closed";

        @Test
        @DisplayName("almost every detector is skipped, which is the entire point of the gates")
        void skipsAlmostEverything() {
            TextSignals signals = TextSignals.of(CLEAN);

            long running = Detectors.defaults().stream()
                    .filter(detector -> detector.mightMatch(signals))
                    .count();

            // Loose on purpose — this is a bound, not a golden number, and a new detector should not
            // fail it. What it would catch is a gate rewritten into something that always answers
            // yes, which would put the whole regex fan-out back on the clean path without a symptom.
            assertThat(running).isLessThanOrEqualTo(2);
        }

        @Test
        @DisplayName("and a log line with an order number and a timestamp still gets a real scan")
        void stillScansOrdinaryLogLines() {
            // Digits and colons are ordinary in a log line, so several gates open here. That is the
            // honest case: the gates are worth what they are worth on real text, not on text chosen
            // to make them look good.
            TextSignals signals = TextSignals.of("order 8891273 accepted at 12:04:33 by node 7");

            assertThat(Detectors.defaults().stream()
                            .filter(detector -> detector.mightMatch(signals))
                            .count())
                    .isPositive();
        }
    }

    /**
     * Invented, and not a key. Generated for this test and never used to protect anything — a real
     * key must never reach a repository, and a fixture that looks like one is the reason the issue
     * template says so.
     */
    private static final String PEM = """
            -----BEGIN PRIVATE KEY-----
            MC4CAQAwBQYDK2VwBCIEIHRoaXNJc05vdEFSZWFsS2V5SnVzdEFUZXN0Rml4dHVy
            -----END PRIVATE KEY-----""";
}
