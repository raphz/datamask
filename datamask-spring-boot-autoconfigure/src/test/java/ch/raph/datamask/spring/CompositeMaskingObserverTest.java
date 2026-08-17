package ch.raph.datamask.spring;

import static org.assertj.core.api.Assertions.assertThat;

import ch.raph.datamask.api.MaskStrategy;
import ch.raph.datamask.api.PiiCategory;
import ch.raph.datamask.domain.MaskingObserver;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Every observer sees every event, so the audit sink and the metrics do not compete for the "
        + "single slot the engine has")
class CompositeMaskingObserverTest {

    private final Recording first = new Recording();
    private final Recording second = new Recording();
    private final MaskingObserver composite = new CompositeMaskingObserver(List.of(first, second));

    @Test
    @DisplayName("forwards a masked value to all of them")
    void forwardsOnMasked() {
        composite.onMasked("Customer#email", PiiCategory.EMAIL, MaskStrategy.EMAIL);

        assertThat(first.events).containsExactly("masked Customer#email");
        assertThat(second.events).containsExactly("masked Customer#email");
    }

    @Test
    @DisplayName("forwards unannotated PII to all of them, which is the signal an application is most "
            + "likely to have wired to an alert")
    void forwardsOnUnannotatedPii() {
        composite.onUnannotatedPii("logback:message", PiiCategory.IBAN, "iban");

        assertThat(first.events).containsExactly("unannotated logback:message");
        assertThat(second.events).containsExactly("unannotated logback:message");
    }

    @Test
    @DisplayName("forwards failures and depth limits to all of them")
    void forwardsTheRest() {
        composite.onFailure("Customer#email", new IllegalStateException("boom"));
        composite.onDepthLimitExceeded("Portfolio#accounts");

        assertThat(first.events).containsExactly("failure Customer#email", "depth Portfolio#accounts");
        assertThat(second.events).containsExactly("failure Customer#email", "depth Portfolio#accounts");
    }

    @Test
    @DisplayName("forwards the three signals that were being dropped: a declared scan, a truncated "
            + "collection and a truncated string")
    void forwardsTheSignalsAddedLater() {
        composite.onScanned("Note#text", PiiCategory.IBAN, "iban");
        composite.onCollectionTruncated("Portfolio#accounts", 1_000);
        composite.onTextTruncated("logback:message", 8_192);

        assertThat(first.events)
                .containsExactly("scanned Note#text", "truncated Portfolio#accounts", "cut logback:message");
        assertThat(second.events)
                .containsExactly("scanned Note#text", "truncated Portfolio#accounts", "cut logback:message");
    }

    @Test
    @DisplayName("overrides every method the interface declares, because one it does not name is delivered "
            + "to nobody and looks exactly like a signal that never fired")
    void overridesEveryMethod() {
        // The failure this guards against has no symptom: every method on MaskingObserver is
        // default, so a composite that forgets one compiles, runs, and silently swallows it. That
        // is what happened to onScanned and onCollectionTruncated when the observer split added
        // them, and it survived a full green build.
        List<String> declared = List.of(MaskingObserver.class.getDeclaredMethods()).stream()
                .filter(method -> method.getName().startsWith("on"))
                .map(java.lang.reflect.Method::getName)
                .distinct()
                .sorted()
                .toList();
        List<String> overridden = List.of(CompositeMaskingObserver.class.getDeclaredMethods()).stream()
                .filter(method -> method.getName().startsWith("on"))
                .map(java.lang.reflect.Method::getName)
                .distinct()
                .sorted()
                .toList();

        assertThat(overridden).containsExactlyElementsOf(declared);
    }

    @Test
    @DisplayName("copies the list it was given, so an observer cannot be added behind the engine's back")
    void copiesTheList() {
        List<MaskingObserver> mutable = new ArrayList<>(List.of(first));
        MaskingObserver snapshot = new CompositeMaskingObserver(mutable);
        mutable.add(second);

        snapshot.onMasked("Customer#email", PiiCategory.EMAIL, MaskStrategy.EMAIL);

        assertThat(second.events).isEmpty();
    }

    private static final class Recording implements MaskingObserver {

        private final List<String> events = new ArrayList<>();

        @Override
        public void onMasked(String path, PiiCategory category, MaskStrategy strategy) {
            events.add("masked " + path);
        }

        @Override
        public void onUnannotatedPii(String path, PiiCategory category, String detector) {
            events.add("unannotated " + path);
        }

        @Override
        public void onFailure(String path, Throwable error) {
            events.add("failure " + path);
        }

        @Override
        public void onDepthLimitExceeded(String path) {
            events.add("depth " + path);
        }

        @Override
        public void onScanned(String path, PiiCategory category, String detector) {
            events.add("scanned " + path);
        }

        @Override
        public void onCollectionTruncated(String path, int kept) {
            events.add("truncated " + path);
        }

        @Override
        public void onTextTruncated(String path, int scanned) {
            events.add("cut " + path);
        }
    }
}
