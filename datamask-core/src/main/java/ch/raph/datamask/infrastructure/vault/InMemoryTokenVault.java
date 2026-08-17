package ch.raph.datamask.infrastructure.vault;

import ch.raph.datamask.api.PiiCategory;
import ch.raph.datamask.domain.TokenVault;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;

/**
 * A bounded, expiring, in-process vault.
 *
 * <p>Its purpose is the request-scoped round trip: mask a prompt before it goes to a model, then put
 * the real values back into the answer that comes home. It is explicitly <em>not</em> a substitute
 * for a PCI-scoped vault — nothing here is persisted, encrypted at rest, or audited — and it is no
 * longer installed by default, because an unconfigured application getting one silently is how raw
 * PII ends up living in a heap map. See {@link RejectingTokenVault}.
 *
 * <p>Two bounds hold what it retains. A mapping expires after its time to live, so a quiet service
 * does not keep the last values it saw indefinitely; and the map is capped, so a busy one cannot
 * grow without limit. Both evict oldest-first, and an evicted token simply stops resolving —
 * forgetting a mapping discloses nothing, which is the right direction for this trade.
 */
public final class InMemoryTokenVault implements TokenVault {

    private static final int DEFAULT_CAPACITY = 10_000;

    /** Long enough for a request or a model round trip, short enough that a quiet process forgets. */
    private static final Duration DEFAULT_TIME_TO_LIVE = Duration.ofMinutes(15);

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final SecureRandom random = new SecureRandom();
    private final ConcurrentMap<String, Entry> byValue = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Entry> byToken = new ConcurrentHashMap<>();
    private final Queue<Entry> oldestFirst = new ConcurrentLinkedQueue<>();
    private final int capacity;
    private final Duration timeToLive;
    private final Clock clock;

    public InMemoryTokenVault() {
        this(DEFAULT_CAPACITY, DEFAULT_TIME_TO_LIVE);
    }

    public InMemoryTokenVault(int capacity) {
        this(capacity, DEFAULT_TIME_TO_LIVE);
    }

    public InMemoryTokenVault(int capacity, Duration timeToLive) {
        this(capacity, timeToLive, Clock.systemUTC());
    }

    /** The clock is a seam for tests, so expiry can be proved without sleeping through it. */
    public InMemoryTokenVault(int capacity, Duration timeToLive, Clock clock) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1, was " + capacity);
        }
        if (Objects.requireNonNull(timeToLive, "timeToLive").isNegative() || timeToLive.isZero()) {
            throw new IllegalArgumentException("timeToLive must be positive, was " + timeToLive);
        }
        this.capacity = capacity;
        this.timeToLive = timeToLive;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * One value always maps to one token, even under concurrent calls: the whole read-decide-write
     * runs inside {@code compute}, which is atomic per key. The previous check-then-put across two
     * maps could issue two tokens for the same value and strand the loser in the reverse index —
     * a mapping nobody could ever evict, holding a raw value.
     */
    @Override
    public String tokenize(String value, PiiCategory category) {
        if (value == null) {
            return null;
        }
        Instant now = clock.instant();
        Entry entry = byValue.compute(value, (key, existing) -> {
            if (existing != null && existing.isLiveAt(now)) {
                return existing;
            }
            if (existing != null) {
                byToken.remove(existing.token(), existing);
            }
            Entry fresh = new Entry(key, newToken(category), now.plus(timeToLive));
            byToken.put(fresh.token(), fresh);
            oldestFirst.add(fresh);
            return fresh;
        });
        evict(now);
        return entry.token();
    }

    @Override
    public Optional<String> detokenize(String token) {
        if (token == null) {
            return Optional.empty();
        }
        Entry entry = byToken.get(token);
        if (entry == null) {
            return Optional.empty();
        }
        if (!entry.isLiveAt(clock.instant())) {
            forget(entry);
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    /** Forgets every mapping. Call it when a request finishes to bound how long values are held. */
    public void clear() {
        byValue.clear();
        byToken.clear();
        oldestFirst.clear();
    }

    public int size() {
        return byValue.size();
    }

    private String newToken(PiiCategory category) {
        byte[] material = new byte[12];
        random.nextBytes(material);
        return "tok_" + category.name().toLowerCase(Locale.ROOT) + "_" + ENCODER.encodeToString(material);
    }

    private void evict(Instant now) {
        while (true) {
            Entry oldest = oldestFirst.peek();
            if (oldest == null || (oldest.isLiveAt(now) && byValue.size() <= capacity)) {
                return;
            }
            Entry polled = oldestFirst.poll();
            if (polled == null) {
                return;
            }
            // Possibly not the entry that was peeked, if another thread polled first — which is
            // fine: it is still among the oldest, and dropping a mapping early only ever loses
            // the ability to reverse a token.
            forget(polled);
        }
    }

    /** Two-argument removes, so evicting a stale entry cannot delete the fresh one that replaced it. */
    private void forget(Entry entry) {
        byValue.remove(entry.value(), entry);
        byToken.remove(entry.token(), entry);
    }

    private record Entry(String value, String token, Instant expiresAt) {

        boolean isLiveAt(Instant moment) {
            return expiresAt.isAfter(moment);
        }
    }
}
