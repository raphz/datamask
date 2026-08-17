package ch.raph.datamask.spring;

import ch.raph.datamask.api.MaskStrategy;
import ch.raph.datamask.api.PiiCategory;
import ch.raph.datamask.domain.MaskingObserver;
import java.util.List;

/**
 * Fans one masking event out to every observer bean.
 *
 * <p>The engine takes a single observer, and an application usually wants two: metrics and an audit
 * trail. Picking whichever bean the container happened to hand over would silently drop the other,
 * and the one most likely to be dropped is the alert on {@code onUnannotatedPii}.
 *
 * <p>Nothing is caught here. {@link MaskingObserver} says implementations must be cheap and
 * non-throwing, and an observer that breaks that is a bug worth seeing: the engine treats the throw
 * as a masking failure, which redacts the value and reports it. Swallowing it would leave a metric
 * that has quietly stopped counting the signal this library exists to raise.
 *
 * <p><strong>Every method on {@link MaskingObserver} must be overridden here, including any added
 * later.</strong> They are all {@code default}, so a signal this record does not name compiles
 * perfectly and is delivered to nobody — the composite inherits the do-nothing body and swallows it.
 * That is how {@code onScanned} and {@code onCollectionTruncated} went missing when the observer
 * split introduced them, and a dropped signal looks exactly like a signal that never fired.
 * {@code CompositeMaskingObserverTest} counts the methods on the interface against the ones
 * overridden here so the next one cannot be forgotten the same way.
 */
record CompositeMaskingObserver(List<MaskingObserver> observers) implements MaskingObserver {

    CompositeMaskingObserver {
        observers = List.copyOf(observers);
    }

    @Override
    public void onMasked(String path, PiiCategory category, MaskStrategy strategy) {
        for (MaskingObserver observer : observers) {
            observer.onMasked(path, category, strategy);
        }
    }

    @Override
    public void onUnannotatedPii(String path, PiiCategory category, String detector) {
        for (MaskingObserver observer : observers) {
            observer.onUnannotatedPii(path, category, detector);
        }
    }

    @Override
    public void onScanned(String path, PiiCategory category, String detector) {
        for (MaskingObserver observer : observers) {
            observer.onScanned(path, category, detector);
        }
    }

    @Override
    public void onFailure(String path, Throwable error) {
        for (MaskingObserver observer : observers) {
            observer.onFailure(path, error);
        }
    }

    @Override
    public void onDepthLimitExceeded(String path) {
        for (MaskingObserver observer : observers) {
            observer.onDepthLimitExceeded(path);
        }
    }

    @Override
    public void onCollectionTruncated(String path, int kept) {
        for (MaskingObserver observer : observers) {
            observer.onCollectionTruncated(path, kept);
        }
    }

    @Override
    public void onTextTruncated(String path, int scanned) {
        for (MaskingObserver observer : observers) {
            observer.onTextTruncated(path, scanned);
        }
    }
}
