package ch.raph.datamask.infrastructure.detect;

import static org.assertj.core.api.Assertions.assertThat;

import ch.raph.datamask.domain.PiiFinding;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Default detectors")
class DetectorsTest {

    @Test
    @DisplayName("detects an unspaced IBAN")
    void detectsCompactIban() {
        assertThat(Detectors.iban().detect("transfer from CH9300762011623852957 received"))
                .singleElement()
                .satisfies(finding -> assertThat(finding.confident()).isTrue());
    }

    @Test
    @DisplayName("detects an IBAN in its official printed form, final short group included")
    void detectsPrintedIban() {
        List<PiiFinding> findings = Detectors.iban().detect("transfer from CH93 0076 2011 6238 5295 7 received");

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.confident()).isTrue();
            assertThat(finding.length()).isEqualTo("CH93 0076 2011 6238 5295 7".length());
        });
    }

    @Test
    @DisplayName("detects a printed IBAN whose length is a multiple of four")
    void detectsPrintedIbanWithoutShortGroup() {
        assertThat(Detectors.iban().detect("account BE68 5390 0754 7034 opened"))
                .hasSize(1);
    }

    @Test
    @DisplayName("does not report an alphanumeric reference that fails mod-97")
    void ignoresNonIbanReferences() {
        assertThat(Detectors.iban().detect("order CH00 1234 5678 9012 3456 7 shipped"))
                .isEmpty();
    }

    @Test
    @DisplayName("detects compressed IPv6 — the form real addresses are actually written in")
    void detectsCompressedIpv6() {
        assertThat(Detectors.ipv6().detect("connect to 2001:db8::1 refused")).hasSize(1);
        assertThat(Detectors.ipv6().detect("local fe80::1 up")).hasSize(1);
        assertThat(Detectors.ipv6().detect("listening on :: port 8080")).hasSize(1);
    }

    @Test
    @DisplayName("still detects the fully expanded IPv6 form")
    void detectsExpandedIpv6() {
        assertThat(Detectors.ipv6().detect("from 2001:db8:85a3:8d3:1319:8a2e:370:7348 at noon"))
                .hasSize(1);
    }

    @Test
    @DisplayName("does not report timestamps, MAC addresses or scope-resolution operators as IPv6")
    void ignoresIpv6Lookalikes() {
        assertThat(Detectors.ipv6().detect("at 12:34:56 the job ran")).isEmpty();
        assertThat(Detectors.ipv6().detect("mac aa:bb:cc:dd:ee:ff seen")).isEmpty();
        assertThat(Detectors.ipv6().detect("call std::vector::push_back")).isEmpty();
    }

    @Test
    @DisplayName("does not report ordinary uppercase words as BICs")
    void ignoresUppercaseProseAsBic() {
        // Each of these passes the ISO country check by accident — KI is Kiribati, SC the
        // Seychelles, CA Canada, OM Oman — and each appears constantly in real log output.
        assertThat(Detectors.bic().detect("account type CHECKING selected")).isEmpty();
        assertThat(Detectors.bic().detect("DEUTSCHE BANK response received")).isEmpty();
        assertThat(Detectors.bic().detect("APPLICATION started")).isEmpty();
        assertThat(Detectors.bic().detect("table PUBLIC.CUSTOMER(EMAIL)")).isEmpty();
    }

    @Test
    @DisplayName("still detects a BIC in the two shapes real ones take")
    void detectsRealBics() {
        assertThat(Detectors.bic().detect("route via UBSWCHZH80A today")).hasSize(1);
        assertThat(Detectors.bic().detect("route via DEUTDEFFXXX today")).hasSize(1);
        assertThat(Detectors.bic().detect("route via DEUTDEFF500 today")).hasSize(1);
    }

    @Test
    @DisplayName("does not report a BIC-shaped run whose country code is not an ISO one")
    void ignoresUnknownCountryInBic() {
        assertThat(Detectors.bic().detect("code ABCDZZ12 rejected")).isEmpty();
    }
}
