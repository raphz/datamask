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
                        "[A-Za-z0-9._%+\\-]+@[A-Za-z0-9](?:[A-Za-z0-9\\-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9\\-]+)*\\.[A-Za-z]{2,24}"));
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
                true);
    }

    /** Requires Luhn, which is what separates a card number from a correlation id. */
    public static PiiDetector paymentCard() {
        return new RegexDetector(
                "payment-card",
                PiiCategory.PAN,
                Pattern.compile("\\b(\\d(?:[ \\-]?\\d){11,18})\\b"),
                candidate -> Checksums.luhn(Checksums.digitsOnly(candidate)),
                true);
    }

    /** Swiss social insurance number: {@code 756.xxxx.xxxx.xx} with an EAN-13 check digit. */
    public static PiiDetector swissAhv() {
        return new RegexDetector(
                "swiss-ahv",
                PiiCategory.NATIONAL_ID,
                Pattern.compile("\\b(756[.\\- ]?\\d{4}[.\\- ]?\\d{4}[.\\- ]?\\d{2})\\b"),
                Checksums::swissAhv,
                true);
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
                        "(?<![\\w+])(\\+[1-9]\\d{0,2}[ .\\-]?(?:\\(?\\d{1,4}\\)?[ .\\-]?){1,4}\\d{2,4})(?![\\w])"));
    }

    public static PiiDetector ipv4() {
        return new RegexDetector(
                "ipv4",
                PiiCategory.IP_ADDRESS,
                Pattern.compile(
                        "\\b((?:(?:25[0-5]|2[0-4]\\d|1\\d{2}|[1-9]?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1\\d{2}|[1-9]?\\d))\\b"));
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
                true);
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
     * A BIC is four letters, an ISO country code, then two or five alphanumerics. Validating the
     * country against the JDK's ISO list is what stops it matching ordinary uppercase prose.
     */
    public static PiiDetector bic() {
        return new RegexDetector(
                "bic",
                PiiCategory.BIC,
                Pattern.compile("\\b([A-Z]{4}[A-Z]{2}[A-Z0-9]{2}(?:[A-Z0-9]{3})?)\\b"),
                candidate -> ISO_COUNTRIES.contains(candidate.substring(4, 6)),
                true);
    }

    public static PiiDetector jsonWebToken() {
        return new RegexDetector(
                "jwt",
                PiiCategory.CREDENTIAL,
                Pattern.compile("\\b(eyJ[A-Za-z0-9_\\-]{4,}\\.[A-Za-z0-9_\\-]{4,}\\.[A-Za-z0-9_\\-]*)"));
    }

    public static PiiDetector bearerToken() {
        return new RegexDetector(
                "bearer-token",
                PiiCategory.CREDENTIAL,
                Pattern.compile("(?i)\\b(?:bearer|basic)\\s+([A-Za-z0-9._\\-~+/]{8,}={0,2})"));
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
                                + "\\s*[=:]\\s*\"?([^\\s\"',;&]{4,})\"?"));
    }

    public static PiiDetector privateKey() {
        return new RegexDetector(
                "private-key",
                PiiCategory.CREDENTIAL,
                Pattern.compile(
                        "(-----BEGIN (?:[A-Z ]+ )?PRIVATE KEY-----[\\s\\S]*?-----END (?:[A-Z ]+ )?PRIVATE KEY-----)"));
    }
}
