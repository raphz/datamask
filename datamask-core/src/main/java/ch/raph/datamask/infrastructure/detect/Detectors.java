package ch.raph.datamask.infrastructure.detect;

import ch.raph.datamask.api.PiiCategory;
import ch.raph.datamask.domain.PiiDetector;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The detector set enabled by default.
 *
 * <p>Ordering matters: the text sanitiser resolves overlaps in favour of the earlier detector, so
 * the checksum-confirmed identifiers come first. A Swiss AVS number is thirteen digits and would
 * otherwise be swallowed by a looser numeric pattern.
 *
 * <h2>Every detector here declares a gate</h2>
 *
 * {@link RegexDetector#gatedBy} attaches a one-line necessary condition — an {@code @}, twelve
 * digits, six consecutive capitals — checked against a single-pass summary of the text before the
 * pattern runs. On a clean log line most of these answer "no" and the twelve pattern matches that
 * dominated the scan never happen.
 *
 * <p><strong>Each gate is derived from its own pattern, and each is only as safe as that
 * derivation.</strong> A gate that excludes text the pattern would have matched is a value that is
 * never examined and therefore never masked: a leak with no symptom. {@code DetectorGateTest} holds
 * every positive fixture in this file against its detector's gate for that reason, and a new
 * detector belongs in it before it belongs here.
 */
public final class Detectors {

    private static final Set<String> ISO_COUNTRIES = Set.of(Locale.getISOCountries());

    private Detectors() {}

    public static List<PiiDetector> defaults() {
        return List.of(
                privateKey(),
                jsonWebToken(),
                bearerToken(),
                assignedSecret(),
                swissAhv(),
                iban(),
                paymentCard(),
                email(),
                internationalPhone(),
                ipv4(),
                ipv6(),
                bic());
    }

    /** Addresses are unambiguous enough that no confirmation step is needed. */
    public static PiiDetector email() {
        return new RegexDetector(
                        "email",
                        PiiCategory.EMAIL,
                        Pattern.compile(
                                "[A-Za-z0-9._%+\\-]+@[A-Za-z0-9](?:[A-Za-z0-9\\-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9\\-]+)*\\.[A-Za-z]{2,24}"))
                // The one unambiguous gate in the file: no address, in any form, without an @.
                .gatedBy(signals -> signals.contains('@'));
    }

    /**
     * Requires mod-97 to hold, so ordinary alphanumeric references are not reported.
     *
     * <p>Two shapes: unspaced, and the official printed form — groups of four with a final group
     * of one to three characters. The short-group alternative refuses to bind when another
     * alphanumeric run follows, so a match never swallows an unrelated trailing token, which would
     * fail the checksum and silently drop the whole finding (there is no backtracking retry once
     * the checksum has rejected a match).
     */
    public static PiiDetector iban() {
        return new RegexDetector(
                        "iban",
                        PiiCategory.IBAN,
                        Pattern.compile(
                                "\\b([A-Z]{2}[0-9]{2}(?:[A-Z0-9]{11,30}|(?: [A-Z0-9]{4}){2,7}(?: [A-Z0-9]{1,3}(?! ?[A-Z0-9]))?))\\b"),
                        Checksums::iban,
                        true)
                // The country code is two capitals and the check digits are two digits, in every
                // form. Fourteen characters is the shortest the printed form can be.
                .gatedBy(signals -> signals.length() >= 14 && signals.uppercaseLetters() >= 2 && signals.digits() >= 2);
    }

    /** Requires Luhn, which is what separates a card number from a correlation id. */
    public static PiiDetector paymentCard() {
        return new RegexDetector(
                        "payment-card",
                        PiiCategory.PAN,
                        Pattern.compile("\\b(\\d(?:[ \\-]?\\d){11,18})\\b"),
                        candidate -> Checksums.luhn(Checksums.digitsOnly(candidate)),
                        true)
                // Twelve digits is the shortest card the pattern accepts. They may be spread across
                // the text rather than adjacent, so this counts them rather than looking for a run —
                // a spaced number is four runs of four.
                .gatedBy(signals -> signals.digits() >= 12);
    }

    /** Swiss social insurance number: {@code 756.xxxx.xxxx.xx} with an EAN-13 check digit. */
    public static PiiDetector swissAhv() {
        return new RegexDetector(
                        "swiss-ahv",
                        PiiCategory.NATIONAL_ID,
                        Pattern.compile("\\b(756[.\\- ]?\\d{4}[.\\- ]?\\d{4}[.\\- ]?\\d{2})\\b"),
                        Checksums::swissAhv,
                        true)
                // Thirteen digits, three of which are the fixed 756 prefix every AVS number carries.
                .gatedBy(signals -> signals.digits() >= 13 && signals.containsAll("756"));
    }

    /**
     * Only numbers written in international form. A bare run of digits is not distinguishable from
     * a reference number, and reporting it would make scanning unusable.
     */
    public static PiiDetector internationalPhone() {
        return new RegexDetector(
                        "phone-e164",
                        PiiCategory.PHONE,
                        Pattern.compile(
                                "(?<![\\w+])(\\+[1-9]\\d{0,2}[ .\\-]?(?:\\(?\\d{1,4}\\)?[ .\\-]?){1,4}\\d{2,4})(?![\\w])"))
                // International form only, which is the whole reason this detector is usable at all:
                // no plus, no match. Four digits is the shortest number the pattern accepts.
                .gatedBy(signals -> signals.contains('+') && signals.digits() >= 4);
    }

    public static PiiDetector ipv4() {
        return new RegexDetector(
                        "ipv4",
                        PiiCategory.IP_ADDRESS,
                        Pattern.compile(
                                "\\b((?:(?:25[0-5]|2[0-4]\\d|1\\d{2}|[1-9]?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1\\d{2}|[1-9]?\\d))\\b"))
                // Four octets, so three dots and at least four digits.
                .gatedBy(signals -> signals.contains('.') && signals.digits() >= 4);
    }

    /**
     * Matches both the full eight-group form and the compressed {@code ::} form — which is how
     * virtually every real IPv6 address is written. The loose pattern is confirmed by a structural
     * validator, the same shape-then-checksum split the payment detectors use.
     */
    public static PiiDetector ipv6() {
        return new RegexDetector(
                        "ipv6",
                        PiiCategory.IP_ADDRESS,
                        Pattern.compile("(?<![\\w:.])((?:[0-9A-Fa-f]{0,4}:){2,7}[0-9A-Fa-f]{0,4})(?![\\w:])"),
                        Detectors::validIpv6,
                        true)
                // Two colons at the least. Only their presence is summarised, not how many, and a
                // colon is common enough in a log line that this is the weakest gate here — it still
                // takes the detector off every line that has none.
                .gatedBy(signals -> signals.contains(':'));
    }

    /**
     * RFC 4291 structure: at most one {@code ::}, groups of at most four hex digits, and either
     * exactly eight groups or a compression standing in for at least one. The regex has already
     * excluded non-hex characters and stray colons at the edges.
     */
    private static boolean validIpv6(String candidate) {
        int compression = candidate.indexOf("::");
        if (compression >= 0 && candidate.indexOf("::", compression + 1) > compression) {
            return false;
        }
        if (candidate.contains(":::")) {
            return false;
        }
        if (candidate.startsWith(":") && !candidate.startsWith("::")) {
            return false;
        }
        if (candidate.endsWith(":") && !candidate.endsWith("::")) {
            return false;
        }
        int groups = 0;
        for (String group : candidate.split(":", -1)) {
            if (group.isEmpty()) {
                continue;
            }
            if (group.length() > 4) {
                return false;
            }
            groups++;
        }
        return compression >= 0 ? groups <= 7 : groups == 8;
    }

    /**
     * A BIC is four letters, an ISO country code, then two or five alphanumerics.
     *
     * <p>The country check alone was not enough. An all-uppercase word passes it whenever its fifth
     * and sixth letters happen to spell an ISO code, which ordinary log prose does constantly:
     * {@code CHECKING} is Kiribati, {@code DEUTSCHE} is the Seychelles, {@code APPLICATION} is
     * Canada. So a candidate must additionally carry a digit, or end in the {@code XXX} that stands
     * for a primary office — the two shapes real BICs overwhelmingly take, and shapes an English
     * word essentially never does.
     *
     * <p>The cost is deliberate: an all-letter eight-character BIC such as {@code DEUTDEFF} is no
     * longer reported in free text. A BIC names a bank rather than a person, it is still masked
     * wherever it is declared, and the alternative failure is the one that matters — a scanner that
     * garbles every capitalised word in a log is one an operator switches off, taking every other
     * detector with it.
     */
    public static PiiDetector bic() {
        return new RegexDetector(
                        "bic",
                        PiiCategory.BIC,
                        Pattern.compile("\\b([A-Z]{4}[A-Z]{2}[A-Z0-9]{2}(?:[A-Z0-9]{3})?)\\b"),
                        Detectors::validBic,
                        true)
                // Bank code and country code are six capitals with nothing between them; only the
                // two characters after them may be digits. Ordinary sentence case never gets close.
                .gatedBy(signals -> signals.longestUppercaseRun() >= 6);
    }

    private static boolean validBic(String candidate) {
        if (!ISO_COUNTRIES.contains(candidate.substring(4, 6))) {
            return false;
        }
        if (candidate.length() == 11 && candidate.endsWith("XXX")) {
            return true;
        }
        return candidate.chars().anyMatch(Character::isDigit);
    }

    public static PiiDetector jsonWebToken() {
        return new RegexDetector(
                        "jwt",
                        PiiCategory.CREDENTIAL,
                        Pattern.compile("\\b(eyJ[A-Za-z0-9_\\-]{4,}\\.[A-Za-z0-9_\\-]{4,}\\.[A-Za-z0-9_\\-]*)"))
                // Every JWT starts `eyJ` — base64 for `{"` — and carries two dots. The capital J is
                // what makes this the sharpest gate in the file.
                .gatedBy(signals -> signals.containsAll("eyJ."));
    }

    public static PiiDetector bearerToken() {
        return new RegexDetector(
                        "bearer-token",
                        PiiCategory.CREDENTIAL,
                        Pattern.compile("(?i)\\b(?:bearer|basic)\\s+([A-Za-z0-9._\\-~+/]{8,}={0,2})"))
                // Both keywords begin with a b, in either case, and the shortest match is the
                // keyword, a space and eight characters of token.
                .gatedBy(signals -> signals.containsAny("bB") && signals.length() >= 15);
    }

    /**
     * {@code password=hunter2}, {@code "apiKey": "..."} and the like. Configuration and connection
     * strings reach logs far more often than anyone expects.
     */
    public static PiiDetector assignedSecret() {
        return new RegexDetector(
                        "assigned-secret",
                        PiiCategory.CREDENTIAL,
                        Pattern.compile(
                                "(?i)\\b(?:pass(?:word|wd)?|secret|api[_\\-]?key|access[_\\-]?token|client[_\\-]?secret|private[_\\-]?key)"
                                        + "\\s*[=:]\\s*\"?([^\\s\"',;&]{4,})\"?"))
                // Whatever the keyword, something has to be assigned to it.
                .gatedBy(signals -> signals.containsAny("=:"));
    }

    public static PiiDetector privateKey() {
        return new RegexDetector(
                        "private-key",
                        PiiCategory.CREDENTIAL,
                        Pattern.compile(
                                "(-----BEGIN (?:[A-Z ]+ )?PRIVATE KEY-----[\\s\\S]*?-----END (?:[A-Z ]+ )?PRIVATE KEY-----)"))
                // The PEM armour is literal and unmistakable, and its letters are all capitals.
                .gatedBy(signals -> signals.contains('-') && signals.containsAll("BEGIN"));
    }
}
