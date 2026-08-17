package ch.raph.datamask.infrastructure.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.raph.datamask.api.PiiCategory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("In-memory token vault")
class InMemoryTokenVaultTest {

    private static final String IBAN = "CH9300762011623852957";

    /** A clock the test moves by hand, so expiry can be proved without sleeping through it. */
    private static final class MovableClock extends Clock {

        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        void advance(Duration amount) {
            now = now.plus(amount);
        }
    }

    @Test
    @DisplayName("round-trips a value through its token")
    void roundTrips() {
        InMemoryTokenVault vault = new InMemoryTokenVault();

        String token = vault.tokenize(IBAN, PiiCategory.IBAN);

        assertThat(token).startsWith("tok_iban_").doesNotContain(IBAN);
        assertThat(vault.detokenize(token)).contains(IBAN);
    }

    @Test
    @DisplayName("issues exactly one token per value, even when many threads ask at once")
    void issuesOneTokenPerValueUnderConcurrency() throws Exception {
        InMemoryTokenVault vault = new InMemoryTokenVault();
        List<String> tokens = new CopyOnWriteArrayList<>();

        // The old check-then-put across two maps could hand two threads two different tokens for
        // the same value, and strand the loser in the reverse index — a mapping nothing could ever
        // evict, holding a raw IBAN for the life of the process.
        try (var threads = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            IntStream.range(0, 64)
                    .forEach(i -> threads.submit(() -> tokens.add(vault.tokenize(IBAN, PiiCategory.IBAN))));
        }

        assertThat(Set.copyOf(tokens)).hasSize(1);
        assertThat(vault.size()).isEqualTo(1);
        assertThat(vault.detokenize(tokens.getFirst())).contains(IBAN);
    }

    @Test
    @DisplayName("forgets a mapping once its time to live has passed")
    void expiresMappings() {
        MovableClock clock = new MovableClock();
        InMemoryTokenVault vault = new InMemoryTokenVault(100, Duration.ofMinutes(15), clock);
        String token = vault.tokenize(IBAN, PiiCategory.IBAN);

        clock.advance(Duration.ofMinutes(16));

        // Without a TTL the last values a quiet service happened to see sit in the heap forever.
        assertThat(vault.detokenize(token)).isEmpty();
    }

    @Test
    @DisplayName("issues a fresh token after the previous one expired")
    void reissuesAfterExpiry() {
        MovableClock clock = new MovableClock();
        InMemoryTokenVault vault = new InMemoryTokenVault(100, Duration.ofMinutes(15), clock);
        String first = vault.tokenize(IBAN, PiiCategory.IBAN);

        clock.advance(Duration.ofMinutes(16));
        String second = vault.tokenize(IBAN, PiiCategory.IBAN);

        assertThat(second).isNotEqualTo(first);
        assertThat(vault.detokenize(second)).contains(IBAN);
        assertThat(vault.detokenize(first)).isEmpty();
    }

    @Test
    @DisplayName("stays within its capacity, evicting oldest first")
    void staysBounded() {
        InMemoryTokenVault vault = new InMemoryTokenVault(10);

        IntStream.range(0, 100).forEach(i -> vault.tokenize("value-" + i, PiiCategory.UNSPECIFIED));

        assertThat(vault.size()).isLessThanOrEqualTo(10);
    }

    @Test
    @DisplayName("evicting one mapping never orphans the other index")
    void evictionClearsBothIndexes() {
        InMemoryTokenVault vault = new InMemoryTokenVault(5);
        String firstToken = vault.tokenize("value-0", PiiCategory.UNSPECIFIED);

        IntStream.rangeClosed(1, 50).forEach(i -> vault.tokenize("value-" + i, PiiCategory.UNSPECIFIED));

        assertThat(vault.detokenize(firstToken)).isEmpty();
        assertThat(vault.size()).isLessThanOrEqualTo(5);
    }

    @Test
    @DisplayName("the default vault refuses to issue tokens at all")
    void rejectingVaultRefuses() {
        assertThatThrownBy(() -> RejectingTokenVault.INSTANCE.tokenize(IBAN, PiiCategory.IBAN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("needs a TokenVault")
                .satisfies(failure -> assertThat(String.valueOf(failure)).doesNotContain(IBAN));
        assertThat(RejectingTokenVault.INSTANCE.detokenize("tok_iban_anything")).isEmpty();
    }

    @Test
    @DisplayName("keeps no more than one entry per distinct value")
    void deduplicatesValues() {
        InMemoryTokenVault vault = new InMemoryTokenVault();
        var seen = ConcurrentHashMap.<String>newKeySet();

        IntStream.range(0, 20).forEach(i -> seen.add(vault.tokenize(IBAN, PiiCategory.IBAN)));

        assertThat(seen).hasSize(1);
        assertThat(vault.size()).isEqualTo(1);
    }
}
