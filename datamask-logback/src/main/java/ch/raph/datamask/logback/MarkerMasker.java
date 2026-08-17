package ch.raph.datamask.logback;

import ch.raph.datamask.application.MaskingEngine;
import ch.raph.datamask.domain.MaskingObserver;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import org.slf4j.helpers.BasicMarker;

/**
 * Masks the markers of an event — the channel a JSON encoder ships structured payloads on.
 *
 * <p>logstash-logback-encoder attaches whole objects to a line as markers ({@code
 * Markers.append("customer", customer)}), and its encoder serialises them into the shipped JSON.
 * An event whose message, arguments and MDC were all masked would still leak everything such a
 * marker carries, which is why the marker list is treated exactly like the argument array.
 *
 * <h2>What happens to each marker</h2>
 *
 * <ul>
 *   <li>A logstash appending marker is rebuilt around its masked payload by
 *       {@link LogstashMarkerMasker}, which is only loaded once the encoder is known to be on the
 *       classpath — the dependency is optional.
 *   <li>A plain {@link BasicMarker} — the kind every SLF4J factory hands out for filtering — carries
 *       a name and nothing else, and passes through unchanged. Its references are walked, so a
 *       logstash marker attached as a child of a filtering marker is still found.
 *   <li>A marker of any other concrete type may carry a payload nothing here can read, so it is
 *       replaced by a plain name-only marker with the same name: filtering on the name keeps
 *       working, the payload is stripped, and the strip is reported to the observer. Passing it
 *       through on the grounds that it is <em>probably</em> harmless would make an unknown marker
 *       type a way around masking.
 *   <li>A marker this class fails on is replaced by the redaction placeholder — never forwarded.
 * </ul>
 *
 * <p>The same list instance is returned when no marker needed masking, which is what keeps the
 * event-level no-change short-circuit intact.
 */
final class MarkerMasker {

    /**
     * Loading the encoder-specific class is deferred until this is known to be true, which is what
     * keeps logstash-logback-encoder an optional dependency rather than a required one.
     */
    private static final boolean LOGSTASH_PRESENT = isPresent("net.logstash.logback.marker.LogstashMarker");

    private final MaskingEngine engine;
    private final MaskingObserver observer;
    private final boolean scanText;

    MarkerMasker(MaskingEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.observer = engine.observer();
        this.scanText = engine.policy().scanUnannotatedText();
    }

    /** The masked children of a marker, and whether any of them differs from the original. */
    record Children(List<Marker> markers, boolean changed) {}

    /** The same list when no marker carried anything, a copy with masked replacements otherwise. */
    List<Marker> mask(List<Marker> markers, String origin) {
        if (markers == null || markers.isEmpty()) {
            return markers;
        }
        List<Marker> masked = null;
        for (int i = 0; i < markers.size(); i++) {
            Marker marker = markers.get(i);
            Marker safe = maskMarker(marker, origin + "/marker", 0);
            if (safe != marker && masked == null) {
                masked = new ArrayList<>(markers);
            }
            if (masked != null) {
                masked.set(i, safe);
            }
        }
        return masked != null ? masked : markers;
    }

    /**
     * One marker and everything referenced from it: the same instance when the whole subtree was
     * clean, a masked replacement otherwise — and on any failure the redaction placeholder, because
     * a marker that could not be inspected must not be forwarded.
     */
    Marker maskMarker(Marker marker, String basePath, int depth) {
        if (marker == null) {
            return null;
        }
        String path = basePath;
        try {
            path = basePath + "/" + name(marker);
            if (depth > engine.policy().maxDepth()) {
                // Genuinely the depth limit, not a truncation: a marker's references are the levels
                // of a graph, and `depth` counts how far down one this marker sits. The fail-closed
                // way to stop walking it is to stop disclosing.
                observer.onDepthLimitExceeded(path);
                return redacted();
            }
            if (LOGSTASH_PRESENT && LogstashMarkerMasker.handles(marker)) {
                return LogstashMarkerMasker.mask(marker, this, path, depth);
            }
            if (marker.getClass() == BasicMarker.class) {
                Children children = maskChildren(marker, path, depth);
                return children.changed() ? rebuilt(marker.getName(), children) : marker;
            }
            observer.onFailure(
                    path,
                    new UnsupportedOperationException("marker type "
                            + marker.getClass().getName() + " cannot be inspected; its payload was stripped"));
            return rebuilt(marker.getName(), maskChildren(marker, path, depth));
        } catch (Throwable failure) {
            observer.onFailure(path, failure);
            return redacted();
        }
    }

    Children maskChildren(Marker marker, String path, int depth) {
        if (!marker.hasReferences()) {
            return new Children(List.of(), false);
        }
        List<Marker> masked = new ArrayList<>();
        boolean changed = false;
        for (Iterator<Marker> references = marker.iterator(); references.hasNext(); ) {
            Marker child = references.next();
            Marker safe = maskMarker(child, path, depth + 1);
            changed |= safe != child;
            masked.add(safe);
        }
        return new Children(masked, changed);
    }

    /** Mirrors {@code LoggingEventMasker.maskArgument}: a marker payload is an argument by another name. */
    Object maskValue(Object value, String path) {
        if (value == null) {
            return null;
        }
        if (value instanceof Throwable thrown) {
            String rendered = thrown.toString();
            String safe = scan(rendered, path);
            return safe == rendered ? value : safe;
        }
        if (value instanceof CharSequence text) {
            String rendered = text.toString();
            String safe = scan(rendered, path);
            return safe == rendered ? value : safe;
        }
        return engine.mask(value, path);
    }

    String scan(String text, String path) {
        if (!scanText || text == null || text.isEmpty()) {
            return text;
        }
        return engine.maskText(text, path);
    }

    /**
     * Detached rather than from a factory: a factory caches by name, and a rebuilt marker is mutated
     * by adding children, so two events sharing one would accumulate each other's.
     */
    private Marker rebuilt(String name, Children children) {
        Marker marker = MarkerFactory.getDetachedMarker(name);
        children.markers().forEach(marker::add);
        return marker;
    }

    Marker redacted() {
        return MarkerFactory.getDetachedMarker(engine.policy().redactionPlaceholder());
    }

    private String name(Marker marker) {
        return LOGSTASH_PRESENT && LogstashMarkerMasker.handles(marker)
                ? LogstashMarkerMasker.name(marker)
                : marker.getName();
    }

    private static boolean isPresent(String className) {
        try {
            Class.forName(className, false, MarkerMasker.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
