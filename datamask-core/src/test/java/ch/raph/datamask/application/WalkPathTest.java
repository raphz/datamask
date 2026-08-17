package ch.raph.datamask.application;

import static org.assertj.core.api.Assertions.assertThat;

import ch.raph.datamask.api.MaskStrategy;
import ch.raph.datamask.api.PII;
import ch.raph.datamask.api.PiiCategory;
import ch.raph.datamask.domain.MaskingObserver;
import ch.raph.datamask.testdomain.Banking;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The paths the observer is told about, now that they are built as the walk goes.
 *
 * <p>The engine used to concatenate a fresh string per member. It now pushes onto one buffer and
 * pops on the way back out, which is worth a good deal of allocation on a graph with nothing to
 * mask — and which introduces a failure mode the old code could not have: a push that is not undone
 * leaves every path after it wrong. That is not a crash and not a wrong output; it is an observer
 * signal naming a field that had nothing to do with it, which in this library is the difference
 * between finding the field that leaks and looking at the wrong one for a week.
 *
 * <p>So these assert exact paths, in sequence, across every kind of push there is — and, in the
 * last two, across the paths where the walk unwinds early.
 */
@DisplayName("Paths survive the walk")
class WalkPathTest {

    record Leaf(@PII(category = PiiCategory.EMAIL) String email) {}

    record Row(Leaf first, Leaf second) {}

    record Bag(List<Leaf> items) {}

    record Keyed(Map<String, Leaf> byName) {}

    record Nested(Bag bag, Leaf trailing) {}

    record Mixed(Banking.Unrebuildable broken, Leaf trailing) {}

    private final List<String> paths = new ArrayList<>();

    private final DataMask dataMask = DataMask.builder()
            .secret("walk-path-test-secret-not-a-real-key")
            .observer(new MaskingObserver() {
                @Override
                public void onMasked(String path, PiiCategory category, MaskStrategy strategy) {
                    paths.add(path);
                }

                @Override
                public void onFailure(String path, Throwable error) {
                    paths.add("failed:" + path);
                }
            })
            .build();

    private static Leaf leaf() {
        return new Leaf("john.doe@example.com");
    }

    @Test
    @DisplayName("two members of the same object do not inherit each other's segment")
    void siblingsAreIndependent() {
        dataMask.mask(new Row(leaf(), leaf()));

        assertThat(paths).containsExactly("Row.first.email", "Row.second.email");
    }

    @Test
    @DisplayName("list elements are indexed, and the index does not accumulate")
    void listElementsAreIndexed() {
        dataMask.mask(new Bag(List.of(leaf(), leaf(), leaf())));

        assertThat(paths).containsExactly("Bag.items[0].email", "Bag.items[1].email", "Bag.items[2].email");
    }

    @Test
    @DisplayName("map entries are positional, and the key segment does not leak into the value's path")
    void mapEntriesArePositional() {
        Map<String, Leaf> byName = new LinkedHashMap<>();
        byName.put("first", leaf());
        byName.put("second", leaf());

        dataMask.mask(new Keyed(byName));

        assertThat(paths).containsExactly("Keyed.byName{0}.email", "Keyed.byName{1}.email");
    }

    @Test
    @DisplayName("a member after a container starts from the object's path again")
    void unwindsOutOfContainers() {
        dataMask.mask(new Nested(new Bag(List.of(leaf(), leaf())), leaf()));

        assertThat(paths)
                .containsExactly("Nested.bag.items[0].email", "Nested.bag.items[1].email", "Nested.trailing.email");
    }

    @Test
    @DisplayName("a member after one that failed structurally is still named correctly")
    void unwindsOutOfAFailure() {
        // The first member cannot be rebuilt, so its subtree ends in a caught failure and the walk
        // carries on with the second. The second's path has to start from Mixed, not from wherever
        // the failure left the buffer.
        dataMask.mask(new Mixed(new Banking.Unrebuildable(7, "john.doe@example.com"), leaf()));

        assertThat(paths).contains("failed:Mixed.broken", "Mixed.trailing.email");
    }

    @Test
    @DisplayName("a root path from an integration prefixes everything below it")
    void rootPathIsCarried() {
        dataMask.engine().mask(new Row(leaf(), leaf()), "kafka:value/payments");

        assertThat(paths).containsExactly("kafka:value/payments.first.email", "kafka:value/payments.second.email");
    }
}
