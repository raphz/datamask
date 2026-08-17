package ch.raph.datamask.application;

import static org.assertj.core.api.Assertions.assertThat;

import ch.raph.datamask.api.MaskStrategy;
import ch.raph.datamask.api.PII;
import ch.raph.datamask.api.PiiCategory;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The shapes a masked value has to fit back into.
 *
 * <p>Every failure in here looked the same from the outside before it was fixed — the member came
 * back {@code null}, which is a masked-looking result, so nothing about it said "this is a bug"
 * rather than "this was masked". That is what makes these worth pinning.
 */
@DisplayName("Container shapes")
class ContainerShapesTest {

    private final DataMask dataMask =
            DataMask.builder().secret("a-test-secret-of-sufficient-length").build();

    record WithDeque(
            Deque<String> events,
            @PII(category = PiiCategory.EMAIL) String email) {}

    record WithConcurrentMap(
            ConcurrentMap<String, String> byId,
            @PII(category = PiiCategory.EMAIL) String email) {}

    record WithBuilder(
            StringBuilder note,
            @PII(category = PiiCategory.EMAIL) String email) {}

    record WithOptional(@PII(category = PiiCategory.EMAIL) Optional<String> email) {}

    record WithOptionalInt(
            @PII(category = PiiCategory.NATIONAL_ID) OptionalInt reference) {}

    record Clean(Optional<String> note, String country) {}

    record WithUri(URI callback) {}

    @Test
    @DisplayName("a Deque member comes back a Deque, not a null")
    void keepsDequeShape() {
        Deque<String> events = new ArrayDeque<>(List.of("opened", "closed"));

        WithDeque masked = dataMask.mask(new WithDeque(events, "john.doe@example.com"));

        assertThat(masked.events()).isNotNull().containsExactly("opened", "closed");
        assertThat(masked.email()).doesNotContain("john.doe");
    }

    @Test
    @DisplayName("a ConcurrentMap member comes back a ConcurrentMap, not a null")
    void keepsConcurrentMapShape() {
        ConcurrentMap<String, String> byId = new ConcurrentHashMap<>();
        byId.put("a", "opened");

        WithConcurrentMap masked = dataMask.mask(new WithConcurrentMap(byId, "john.doe@example.com"));

        assertThat(masked.byId()).isNotNull().containsEntry("a", "opened");
        assertThat(masked.email()).doesNotContain("john.doe");
    }

    @Test
    @DisplayName("a StringBuilder member is rebuilt from the masked text instead of nulling the whole record")
    void rebuildsStringBuilder() {
        WithBuilder masked =
                dataMask.mask(new WithBuilder(new StringBuilder("write to jane@example.com"), "john.doe@example.com"));

        assertThat(masked).isNotNull();
        assertThat(masked.note()).isNotNull();
        assertThat(masked.note().toString()).doesNotContain("jane@example.com").contains("write to");
        assertThat(masked.email()).doesNotContain("john.doe");
    }

    @Test
    @DisplayName("an annotated Optional is masked through its contents and comes back present")
    void masksInsideOptional() {
        WithOptional masked = dataMask.mask(new WithOptional(Optional.of("john.doe@example.com")));

        // Before the fix the masker was handed the Optional itself, the coercion that followed
        // could not fit text back into one, and the caller got a null Optional — an NPE waiting at
        // the next isPresent().
        assertThat(masked.email()).isNotNull().isPresent();
        assertThat(masked.email().orElseThrow()).doesNotContain("john.doe");
    }

    @Test
    @DisplayName("an empty Optional stays empty rather than becoming null")
    void keepsEmptyOptional() {
        assertThat(dataMask.mask(new WithOptional(Optional.empty())).email()).isEmpty();
    }

    @Test
    @DisplayName("an annotated OptionalInt comes back holding its type's zero")
    void masksOptionalInt() {
        WithOptionalInt masked = dataMask.mask(new WithOptionalInt(OptionalInt.of(4711)));

        assertThat(masked.reference()).isPresent();
        assertThat(masked.reference().getAsInt()).isZero();
    }

    @Test
    @DisplayName("a record whose only Optional was unchanged is returned as the same instance")
    void shortCircuitsUnchangedOptional() {
        Clean clean = new Clean(Optional.of("nothing sensitive here"), "CH");

        // Re-wrapping an unchanged value would allocate a new Optional, which reads as a change and
        // forces a rebuild of every enclosing object on every clean graph.
        assertThat(dataMask.mask(clean)).isSameAs(clean);
    }

    @Test
    @DisplayName("PII in a URI query string is scanned, and the member stays a URI")
    void scansUriQuery() {
        WithUri masked =
                dataMask.mask(new WithUri(URI.create("https://api.example.com/cb?email=john.doe@example.com")));

        assertThat(masked.callback()).isNotNull();
        assertThat(masked.callback().toString())
                .doesNotContain("john.doe@example.com")
                .startsWith("https://api.example.com/cb");
    }

    @Test
    @DisplayName("a URI with nothing to mask is returned untouched")
    void leavesCleanUriAlone() {
        WithUri clean = new WithUri(URI.create("https://api.example.com/callback"));

        assertThat(dataMask.mask(clean)).isSameAs(clean);
    }

    @Test
    @DisplayName("a self-referential array keeps its shape instead of unrolling to the depth limit")
    void reproducesArrayCycle() {
        Object[] events = new Object[2];
        events[0] = "write to john.doe@example.com";
        events[1] = events;

        Object[] masked = (Object[]) dataMask.mask((Object) events);

        assertThat(masked).isNotSameAs(events);
        assertThat(String.valueOf(masked[0])).doesNotContain("john.doe@example.com");
        // The cycle is reproduced against the copy, which holds only masked values. Unrolling it
        // instead produced a 32-deep tree; an array referencing itself twice unrolled to 2^32.
        assertThat(masked[1]).isSameAs(masked);
    }

    @Test
    @DisplayName("a branching self-reference terminates instead of exploding")
    void terminatesOnBranchingSelfReference() {
        Object[] events = new Object[2];
        events[0] = events;
        events[1] = events;

        Object[] masked = (Object[]) dataMask.mask((Object) events);

        assertThat(masked[0]).isSameAs(masked);
        assertThat(masked[1]).isSameAs(masked);
    }

    @Test
    @DisplayName("a list that contains itself is masked once, not once per level of depth")
    void reproducesListCycle() {
        List<Object> events = new java.util.ArrayList<>();
        events.add("write to john.doe@example.com");
        events.add(events);

        @SuppressWarnings("unchecked")
        List<Object> masked = dataMask.mask(events);

        assertThat(String.valueOf(masked.getFirst())).doesNotContain("john.doe@example.com");
        assertThat(masked.get(1)).isSameAs(masked);
    }

    @Test
    @DisplayName("a masked null is dropped from a collection that refuses nulls, not thrown out of the whole graph")
    void dropsNullFromNullHostileCollection() {
        record Item(
                @PII(strategy = MaskStrategy.NULLIFY, category = PiiCategory.EMAIL)
                String email) {}
        record Holder(Deque<Item> items) {}

        Deque<Item> items = new ArrayDeque<>(List.of(new Item("john.doe@example.com")));

        Holder masked = dataMask.mask(new Holder(items));

        assertThat(masked).isNotNull();
        assertThat(masked.items()).allSatisfy(item -> assertThat(item.email()).isNull());
    }
}
