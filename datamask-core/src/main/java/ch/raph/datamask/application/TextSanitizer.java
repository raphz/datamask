package ch.raph.datamask.application;

import ch.raph.datamask.api.MaskStrategy;
import ch.raph.datamask.api.Masker;
import ch.raph.datamask.api.PiiCategory;
import ch.raph.datamask.api.Sensitivity;
import ch.raph.datamask.domain.MaskingObserver;
import ch.raph.datamask.domain.MaskingPolicy;
import ch.raph.datamask.domain.PiiDescriptor;
import ch.raph.datamask.domain.PiiDetector;
import ch.raph.datamask.domain.PiiFinding;
import ch.raph.datamask.domain.TextSignals;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Masks the PII inside free-form text while leaving the surrounding prose readable.
 *
 * <p>This is what covers the leaks annotations cannot: an exception message quoting a row, a
 * payment reference the customer typed their own IBAN into, a support note, a prompt assembled
 * from several sources.
 *
 * <h2>Two bounds on what scanning costs</h2>
 *
 * Detectors are gated: each declares a cheap necessary condition, checked once against a one-pass
 * summary of the text, and one that cannot match does not run. See {@link PiiDetector#mightMatch}.
 *
 * <p>And the text itself is bounded by {@link MaskingPolicy#maxTextLength()}. Scanning is linear in
 * the length of the string, so without a cap one oversized value — a payload logged whole, a stack
 * trace with a body in it — costs the caller milliseconds. Past the cap the text is redacted rather
 * than emitted unscanned, which is the fail-closed direction: the bound costs output, never
 * disclosure.
 */
public final class TextSanitizer {

    private static final Comparator<PiiFinding> DOCUMENT_ORDER = Comparator.comparingInt(PiiFinding::start)
            .thenComparing(Comparator.comparingInt(PiiFinding::length).reversed());

    /**
     * How far past the cap the scan looks, purely to find a value that straddles it.
     *
     * <p>Cutting a string at a fixed offset can land in the middle of an identifier, and the head of
     * a card number left in the kept prefix is a partial disclosure the cap would have caused rather
     * than prevented. So the scan reads a little further than it keeps, and when a finding crosses
     * the cap the cut moves back to where that finding starts. Sixty-four characters covers every
     * identifier the built-in detectors recognise — the longest, an IBAN in printed form, is
     * forty-two.
     */
    private static final int STRADDLE_MARGIN = 64;

    private final List<PiiDetector> detectors;
    private final MaskerRegistry maskers;
    private final MaskContextFactory contexts;
    private final MaskingObserver observer;
    private final MaskingPolicy policy;

    public TextSanitizer(
            List<PiiDetector> detectors,
            MaskerRegistry maskers,
            MaskContextFactory contexts,
            MaskingObserver observer,
            MaskingPolicy policy) {
        this.detectors = List.copyOf(detectors);
        this.maskers = maskers;
        this.contexts = contexts;
        this.observer = observer;
        this.policy = Objects.requireNonNull(policy, "policy");
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
    public @Nullable String sanitize(@Nullable CharSequence text, String path) {
        return sanitize(text, path, null, false);
    }

    /**
     * The same, for a value explicitly declared as free text — a {@code FREEFORM_TEXT} category or a
     * {@code SCAN} strategy. Findings are reported to {@link MaskingObserver#onScanned}: the
     * scanner doing its declared job is not the same event as PII turning up where nobody expected
     * it, and reporting both as the latter is what makes an alert on it unusable.
     */
    public @Nullable String sanitizeDeclared(@Nullable CharSequence text, String path) {
        return sanitize(text, path, null, true);
    }

    /** The engine's entry point, which knows its path but has not built the string for it. */
    @Nullable
    String sanitize(@Nullable CharSequence text, WalkPath path, boolean declared) {
        return sanitize(text, null, path, declared);
    }

    /**
     * Exactly one of {@code path} and {@code walk} is set. The split is what keeps a path from being
     * concatenated for every string on a clean graph: the engine hands over the buffer it is already
     * carrying, and it becomes a string only if this text turns out to contain something.
     */
    private @Nullable String sanitize(
            @Nullable CharSequence text, @Nullable String path, @Nullable WalkPath walk, boolean declared) {
        if (text == null) {
            return null;
        }
        if (text.isEmpty() || detectors.isEmpty()) {
            return text.toString();
        }

        int limit = policy.maxTextLength();
        if (text.length() > limit) {
            return sanitizeBounded(text, limit, path, walk, declared);
        }

        List<PiiFinding> findings = scan(text, TextSignals.of(text));
        if (findings.isEmpty()) {
            // The same instance for a String, which is what preserves the engine's no-change
            // short-circuit all the way up to the object that held it.
            return text.toString();
        }
        return rewrite(text, findings, text.length(), false, path, walk, declared);
    }

    /** Text past {@link MaskingPolicy#maxTextLength()}: scan the head, redact the rest unread. */
    private String sanitizeBounded(
            CharSequence text, int limit, @Nullable String path, @Nullable WalkPath walk, boolean declared) {
        CharSequence head = text.subSequence(0, Math.min(text.length(), limit + STRADDLE_MARGIN));
        List<PiiFinding> found = scan(head, TextSignals.of(head));

        int cut = limit;
        for (PiiFinding finding : found) {
            if (finding.start() < limit && finding.end() > limit) {
                cut = finding.start();
                break;
            }
        }

        List<PiiFinding> kept = new ArrayList<>(found.size());
        for (PiiFinding finding : found) {
            if (finding.end() <= cut) {
                kept.add(finding);
            }
        }
        return rewrite(text, kept, cut, true, path, walk, declared);
    }

    private String rewrite(
            CharSequence text,
            List<PiiFinding> findings,
            int end,
            boolean truncated,
            @Nullable String path,
            @Nullable WalkPath walk,
            boolean declared) {
        String resolved = path != null ? path : String.valueOf(walk);

        StringBuilder out = new StringBuilder(end + 16);
        int cursor = 0;
        for (PiiFinding finding : findings) {
            out.append(text, cursor, finding.start());
            out.append(maskSpan(text.subSequence(finding.start(), finding.end()).toString(), finding, resolved));
            cursor = finding.end();
            if (declared) {
                observer.onScanned(resolved, finding.category(), finding.detector());
            } else {
                observer.onUnannotatedPii(resolved, finding.category(), finding.detector());
            }
        }
        out.append(text, cursor, end);
        if (truncated) {
            observer.onTextTruncated(resolved, end);
            out.append(policy.redactionPlaceholder());
        }
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
     *
     * <p>Unlike {@link #sanitize}, this reads the whole text however long it is.
     * {@link MaskingPolicy#maxTextLength()} bounds what masking costs on a hot path; this is an
     * audit call a caller made deliberately, and answering it about half a document would be worse
     * than answering it slowly.
     */
    public List<PiiFinding> scan(@Nullable CharSequence text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        return scan(text, TextSignals.of(text));
    }

    private List<PiiFinding> scan(CharSequence text, TextSignals signals) {
        List<PiiFinding> all = null;
        for (PiiDetector detector : detectors) {
            if (!detector.mightMatch(signals)) {
                continue;
            }
            List<PiiFinding> found = detector.detect(text);
            if (found.isEmpty()) {
                continue;
            }
            if (all == null) {
                all = new ArrayList<>(found.size() + 4);
            }
            all.addAll(found);
        }
        if (all == null) {
            // Nothing matched, which is the answer on nearly every string this library ever sees.
            return List.of();
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
    public Optional<PiiCategory> classify(@Nullable CharSequence text) {
        if (text == null || text.isEmpty()) {
            return Optional.empty();
        }
        String trimmed = text.toString().trim();
        TextSignals signals = TextSignals.of(trimmed);
        for (PiiDetector detector : detectors) {
            if (!detector.mightMatch(signals)) {
                continue;
            }
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
