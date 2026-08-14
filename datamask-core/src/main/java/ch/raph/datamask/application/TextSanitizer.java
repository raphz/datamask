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

    /** Returns the text with every detected value masked, or the same instance when nothing matched. */
    public String sanitize(CharSequence text, String path) {
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
            observer.onUnannotatedPii(path, finding.category(), finding.detector());
        }
        out.append(text, cursor, text.length());
        return out.toString();
    }

    /**
     * Findings in document order with overlaps removed.
     *
     * <p>Overlaps are real: a card number is also a run of digits, and an IBAN contains what looks
     * like one. The earliest match wins, then the longest, then the higher-priority detector — the
     * list order in {@code Detectors.defaults()} is the priority.
     */
    public List<PiiFinding> scan(CharSequence text) {
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
        MaskStrategy strategy = finding.category().defaultStrategy();
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
