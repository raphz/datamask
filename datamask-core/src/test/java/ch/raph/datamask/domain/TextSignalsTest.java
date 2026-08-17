package ch.raph.datamask.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("The one-pass text summary")
class TextSignalsTest {

    @Test
    @DisplayName("counts digits wherever they are, not only in runs")
    void countsDigitsAnywhere() {
        assertThat(TextSignals.of("4111 1111 1111 1111").digits()).isEqualTo(16);
    }

    @Test
    @DisplayName("reports the longest unbroken run of capitals")
    void reportsLongestUppercaseRun() {
        assertThat(TextSignals.of("route via UBSWCHZH80A today").longestUppercaseRun())
                .isEqualTo(8);
        assertThat(TextSignals.of("Settlement Batch Accepted").longestUppercaseRun())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("answers presence for the characters a gate asks about")
    void answersPresence() {
        TextSignals signals = TextSignals.of("wrote to john.doe@example.com");

        assertThat(signals.contains('@')).isTrue();
        assertThat(signals.contains('+')).isFalse();
        assertThat(signals.containsAll("eyJ")).isFalse();
        assertThat(signals.containsAny("=:")).isFalse();
    }

    @Test
    @DisplayName("reports a character it does not track as present, so an unknown costs a scan and never a leak")
    void unknownCharactersAreReportedPresent() {
        // Non-ASCII is outside the summary. Answering "absent" would let a gate skip a detector
        // whose pattern could have matched, which is the one failure this class must not have.
        assertThat(TextSignals.of("nothing unusual here").contains('é')).isTrue();
    }

    @Test
    @DisplayName("a non-ASCII character breaks a run of capitals, as the pattern would see it")
    void nonAsciiBreaksUppercaseRuns() {
        assertThat(TextSignals.of("UBSWCHZHé80A").longestUppercaseRun()).isEqualTo(8);
    }

    @Test
    @DisplayName("empty and null text contain nothing")
    void emptyTextContainsNothing() {
        assertThat(TextSignals.of("").length()).isZero();
        assertThat(TextSignals.of(null).digits()).isZero();
        assertThat(TextSignals.of(null).contains('@')).isFalse();
    }

    @Test
    @DisplayName("says nothing about the text it summarised when printed")
    void printsNoContent() {
        // It reaches a test failure message and, through one, a public CI run summary.
        assertThat(TextSignals.of("CH9300762011623852957").toString()).doesNotContain("CH93");
    }
}
