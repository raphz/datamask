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
    }
}
