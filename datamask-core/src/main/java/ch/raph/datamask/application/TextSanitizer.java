package ch.raph.datamask.application;

import ch.raph.datamask.api.MaskStrategy;
import ch.raph.datamask.api.Masker;
import ch.raph.datamask.api.PiiCategory;
import ch.raph.datamask.api.Sensitivity;
import ch.raph.datamask.domain.MaskingObserver;
import ch.raph.datamask.domain.PiiDescriptor;
import ch.raph.datamask.domain.PiiDetector;
import ch.raph.datamask.domain.PiiFinding;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Masks the PII inside free-form text while leaving the surrounding prose readable.
 *
 * <p>This is what covers the leaks annotations cannot: an exception message quoting a row, a
 * payment reference the customer typed their own IBAN into, a support note, a prompt assembled
 * from several sources.
 */
public final class TextSanitizer {

    private static final Comparator<PiiFinding> DOCUMENT_ORDER = Comparator.comparingInt(PiiFinding::start)
            .thenComparing(Comparator.comparingInt(PiiFinding::length).reversed());

    private final List<PiiDetector> detectors;
    private final MaskerRegistry maskers;
    private final MaskContextFactory contexts;
    private final MaskingObserver observer;

    public TextSanitizer(
            List<PiiDetector> detectors,
            MaskerRegistry maskers,
            MaskContextFactory contexts,
            MaskingObserver observer) {
        this.detectors = List.copyOf(detectors);
        this.maskers = maskers;
        this.contexts = contexts;
        this.observer = observer;
    }

    /**
     * Returns the text with every detected value masked, or the same instance when nothing matched.
     *
     * <p>For text nobody declared as such, which is why every finding reaches
     * {@link MaskingObserver#onUnannotatedPii} — a hit here means a field is carrying personal data
     * its author did not know about. Text that <em>was</em> declared for scanning goes through
     * {@link #sanitizeDeclared} instead, so it does not dilute that signal.
     *
     * <p>{@code null} in, {@code null} out.
     */
    public String sanitize(CharSequence text, String path) {
        return sanitize(text, path, false);
    }

    /**
     * The same, for a value explicitly declared as free text — a {@code FREEFORM_TEXT} category or a
     * {@code SCAN} strategy. Findings are reported to {@link MaskingObserver#onScanned}: the
     * scanner doing its declared job is not the same event as PII turning up where nobody expected
     * it, and reporting both as the latter is what makes an alert on it unusable.
     */
    public String sanitizeDeclared(CharSequence text, String path) {
        return sanitize(text, path, true);
    }

    private String sanitize(CharSequence text, String path, boolean declared) {
        if (text == null || text.isEmpty() || detectors.isEmpty()) {
            return text == null ? null : text.toString();
        }

        List<PiiFinding> findings = scan(text);
        if (findings.isEmpty()) {
            return text.toString();
        }

        StringBuilder out = new StringBuilder(text.length());
        int cursor = 0;
        for (PiiFinding finding : findings) {
            out.append(text, cursor, finding.start());
            out.append(maskSpan(text.subSequence(finding.start(), finding.end()).toString(), finding, path));
            cursor = finding.end();
            if (declared) {
                observer.onScanned(path, finding.category(), finding.detector());
            } else {
                observer.onUnannotatedPii(path, finding.category(), finding.detector());
            }
        }
        out.append(text, cursor, text.length());
        return out.toString();
    }

    /**
     * Findings in document order with overlaps resolved to their union.
     *
     * <p>Overlaps are real: a card number is also a run of digits, and an IBAN contains what looks
     * like one. The earliest match wins, then the longest, then the higher-priority detector — the
     * list order in {@code Detectors.defaults()} is the priority. A finding that overlaps a kept
     * one but extends past it is not dropped whole: its uncovered tail is text a detector
     * classified as PII, so it is kept as a low-confidence fragment and masked too.
     *
     * <p>Null or empty text has no findings, rather than being a way to get a {@code
     * NullPointerException} out of an auditing call.
     */
    public List<PiiFinding> scan(CharSequence text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<PiiFinding> all = new ArrayList<>();
        for (PiiDetector detector : detectors) {
            all.addAll(detector.detect(text));
        }
        // A stable sort leaves equally-placed findings in detector order, which is the tie-break.
        all.sort(DOCUMENT_ORDER);

        List<PiiFinding> kept = new ArrayList<>(all.size());
        int consumedTo = 0;
        for (PiiFinding finding : all) {
            if (finding.start() >= consumedTo) {
                kept.add(finding);
                consumedTo = finding.end();
            } else if (finding.end() > consumedTo) {
                // Marked not confident: masking decides from that flag, and a fragment must never
                // drive a format masker whose reveal positions assume it sees a whole value.
                kept.add(new PiiFinding(consumedTo, finding.end(), finding.category(), finding.detector(), false));
                consumedTo = finding.end();
            }
        }
        return kept;
    }

    /**
     * What the whole value is, when a single detector matches it end to end. Used to resolve
     * {@link MaskStrategy#AUTO} from a value's own content when nothing else declared it.
     */
    public Optional<PiiCategory> classify(CharSequence text) {
        if (text == null || text.isEmpty()) {
            return Optional.empty();
        }
        String trimmed = text.toString().trim();
        for (PiiDetector detector : detectors) {
            for (PiiFinding finding : detector.detect(trimmed)) {
                if (finding.start() == 0 && finding.end() == trimmed.length()) {
                    return Optional.of(finding.category());
                }
            }
        }
        return Optional.empty();
    }

    private String maskSpan(String span, PiiFinding finding, String path) {
        // An unconfirmed match — a checksum that did not hold, or the tail fragment of an overlap —
        // is fully redacted: a format-preserving mask would reveal characters at positions chosen
        // for a value the detector was not sure about.
        MaskStrategy strategy = finding.confident() ? finding.category().defaultStrategy() : MaskStrategy.REDACT;
        if (strategy == MaskStrategy.AUTO || strategy == MaskStrategy.SCAN) {
            // Nothing more specific applies, and re-entering the scanner here would not terminate.
            strategy = MaskStrategy.REDACT;
        }
        PiiDescriptor descriptor =
                new PiiDescriptor(finding.category(), Sensitivity.HIGH, strategy, -1, '*', "", Masker.class, "");
        Object masked =
                maskers.forStrategy(strategy).mask(span, contexts.create(descriptor, strategy, path, String.class));
        return masked == null ? "" : masked.toString();
    }
}
