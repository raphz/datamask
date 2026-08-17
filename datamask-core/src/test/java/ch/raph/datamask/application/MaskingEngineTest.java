package ch.raph.datamask.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.raph.datamask.api.MaskStrategy;
import ch.raph.datamask.api.PII;
import ch.raph.datamask.api.PiiCategory;
import ch.raph.datamask.domain.FailureMode;
import ch.raph.datamask.domain.MaskingException;
import ch.raph.datamask.domain.MaskingObserver;
import ch.raph.datamask.domain.MaskingPolicy;
import ch.raph.datamask.testdomain.Banking;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Graph traversal")
class MaskingEngineTest {

    private final DataMask dataMask =
            DataMask.builder().secret("a-test-secret-of-sufficient-length").build();

    @Test
    @DisplayName("masks through nesting, lists and maps")
    void masksNestedStructures() {
        Banking.Portfolio portfolio = new Banking.Portfolio(
                "PF-1",
                new Banking.Customer(new Banking.Email("john.doe@example.com"), "CH9300762011623852957", "CH"),
                List.of(new Banking.Account("CH9300762011623852957", new BigDecimal("10.00"), "CHF")),
                Map.of("main", new Banking.Card("4111111111111111", "123", "John Doe")));

        Banking.Portfolio masked = dataMask.mask(portfolio);

        assertThat(masked.owner().email().value()).isEqualTo("j*******@e******.com");
        assertThat(masked.accounts().getFirst().iban()).isEqualTo("CH93 **** **** **** *295 7");
        assertThat(masked.cardsByAlias().get("main").number()).isEqualTo("**** **** **** 1111");
        assertThat(masked.reference()).isEqualTo("PF-1");
    }

    @Test
    @DisplayName("returns the very same instance when a graph holds no PII at all")
    void avoidsCopyingCleanGraphs() {
        record Address(String city, String country) {}
        Address address = new Address("Geneva", "CH");

        assertThat(dataMask.mask(address)).isSameAs(address);
    }

    @Test
    @DisplayName("terminates on a graph that points back at itself")
    void handlesCycles() {
        Banking.Node first = Banking.Node.of("john.doe@example.com");
        Banking.Node second = Banking.Node.of("jane.roe@example.com");
        first.linkTo(second);
        second.linkTo(first);

        Banking.Node masked = dataMask.mask(first);

        assertThat(masked.getEmail()).isEqualTo("j*******@e******.com");
        assertThat(masked.getNext().getEmail()).isEqualTo("j*******@e******.com");
    }

    @Test
    @DisplayName("nulls a back-reference rather than planting the raw original inside the masked graph")
    void cutsCyclesFailClosed() {
        Banking.Node first = Banking.Node.of("john.doe@example.com");
        Banking.Node second = Banking.Node.of("jane.roe@example.com");
        first.linkTo(second);
        second.linkTo(first);

        Banking.Node masked = dataMask.mask(first);

        // The back-reference must not be the original node: its email is still unmasked.
        assertThat(masked.getNext().getNext()).isNull();
    }

    @Test
    @DisplayName("masks a node shared by two branches twice over, because a DAG is not a cycle")
    void doesNotMistakeSharingForACycle() {
        record Pair(Banking.Node left, Banking.Node right) {}
        Banking.Node shared = Banking.Node.of("john.doe@example.com");

        Pair masked = dataMask.mask(new Pair(shared, shared));

        // Cutting a back-reference must not cut a second, sequential visit to the same instance:
        // the identity set is scoped to the path being walked, not to the whole traversal.
        assertThat(masked.left()).isNotNull();
        assertThat(masked.right()).isNotNull();
        assertThat(masked.left().getEmail()).isEqualTo("j*******@e******.com");
        assertThat(masked.right().getEmail()).isEqualTo("j*******@e******.com");
    }

    @Test
    @DisplayName("degrades an unrebuildable bean per the failure policy instead of crashing the caller")
    void redactsUnrebuildableBean() {
        Banking.Unrebuildable masked = dataMask.mask(new Banking.Unrebuildable(7, "john.doe@example.com"));

        assertThat(masked).isNull();
    }

    @Test
    @DisplayName("surfaces an unrebuildable bean under FailureMode.THROW without echoing the value")
    void throwsOnUnrebuildableBeanWhenAsked() {
        DataMask throwing = DataMask.builder()
                .secret("a-test-secret-of-sufficient-length")
                .policy(MaskingPolicy.strict().withFailureMode(FailureMode.THROW))
                .build();

        assertThatThrownBy(() -> throwing.mask(new Banking.Unrebuildable(7, "john.doe@example.com")))
                .isInstanceOf(MaskingException.class)
                .hasMessageContaining("could not build a masked copy")
                .satisfies(e -> assertThat(String.valueOf(e)).doesNotContain("john.doe@example.com"));
    }

    @Test
    @DisplayName("still passes an unrebuildable but PII-free bean through, via the no-change short-circuit")
    void keepsCleanUnrebuildableBean() {
        Banking.UnrebuildableClean clean = new Banking.UnrebuildableClean(true, 7);

        assertThat(dataMask.mask(clean)).isSameAs(clean);
    }

    @Test
    @DisplayName("treats a type whose plan failed to compile as a structural failure, never a pass-through")
    void failsClosedOnFailedPlan() {
        List<String> failures = new CopyOnWriteArrayList<>();
        DataMask failing = DataMask.builder()
                .secret("a-test-secret-of-sufficient-length")
                .compiler(type -> ch.raph.datamask.domain.MaskPlan.failed(type, "inaccessible in this test"))
                .observer(new MaskingObserver() {
                    @Override
                    public void onFailure(String path, Throwable failure) {
                        failures.add(String.valueOf(failure));
                    }
                })
                .build();

        assertThat(failing.mask(new Banking.LowRisk("john.doe@example.com"))).isNull();
        assertThat(failures).isNotEmpty();
        assertThat(failures.getFirst()).doesNotContain("john.doe@example.com");
    }

    @Test
    @DisplayName("masks card data and credentials under any threshold — no policy can switch them off")
    void masksCriticalCategoriesUnderAnyThreshold() {
        record Payment(
                @PII(category = PiiCategory.PAN) String card,
                @PII(category = PiiCategory.CREDENTIAL) String apiKey,
                @PII(category = PiiCategory.EMAIL) String email) {}
        DataMask lax = DataMask.builder()
                .secret("a-test-secret-of-sufficient-length")
                .policy(MaskingPolicy.strict()
                        .withThreshold(ch.raph.datamask.api.Sensitivity.CRITICAL)
                        .withScanUnannotatedText(false))
                .build();

        Payment masked = lax.mask(new Payment("4111111111111111", "sk_live_0123456789", "john.doe@example.com"));

        assertThat(masked.card()).isEqualTo("**** **** **** 1111");
        assertThat(masked.apiKey()).isEqualTo("****");
        // EMAIL classifies HIGH, so the CRITICAL threshold deliberately leaves it alone.
        assertThat(masked.email()).isEqualTo("john.doe@example.com");
    }

    @Test
    @DisplayName("refuses a partially-revealing strategy on a category that must never be partially revealed")
    void refusesRevealingStrategyOnNeverRevealCategory() {
        record Leaky(
                @PII(category = PiiCategory.CREDENTIAL, strategy = MaskStrategy.PAN, keep = 4)
                String secret) {}

        Leaky masked = dataMask.mask(new Leaky("40000000000000000000"));

        assertThat(masked.secret()).isEqualTo("****").doesNotContain("0000");
    }

    @Test
    @DisplayName("hardens a never-reveal category on maskDeclared too, the entry point integrations use")
    void hardensTheIntegrationEntryPoint() {
        Object masked = dataMask.engine()
                .maskDeclared(
                        "40000000000000000000",
                        new ch.raph.datamask.domain.PiiDescriptor(
                                PiiCategory.CARD_VERIFICATION_VALUE,
                                ch.raph.datamask.api.Sensitivity.HIGH,
                                MaskStrategy.PAN,
                                4,
                                '*',
                                "",
                                ch.raph.datamask.api.Masker.class,
                                ""),
                        String.class,
                        "jdbc:param/1");

        assertThat(masked).hasToString("****");
    }

    @Test
    @DisplayName("keeps map keys out of observer paths — a map is often keyed by exactly the PII being hidden")
    void neverPutsMapKeysInPaths() {
        List<String> paths = new CopyOnWriteArrayList<>();
        DataMask observed = DataMask.builder()
                .secret("a-test-secret-of-sufficient-length")
                .observer(new MaskingObserver() {
                    @Override
                    public void onMasked(String path, PiiCategory category, MaskStrategy strategy) {
                        paths.add(path);
                    }

                    @Override
                    public void onUnannotatedPii(String path, PiiCategory category, String detector) {
                        paths.add(path);
                    }
                })
                .build();

        observed.mask(Map.of("john.doe@example.com", new Banking.Card("4111111111111111", "123", "John Doe")));

        assertThat(paths)
                .isNotEmpty()
                .allSatisfy(path -> assertThat(path).doesNotContain("john.doe").doesNotContain("@"));
    }

    @Test
    @DisplayName("rebuilds a bean through its all-arguments constructor")
    void rebuildsBeanWithAllArgsConstructor() {
        Banking.LegacyCustomer masked = dataMask.mask(new Banking.LegacyCustomer("john.doe@example.com", "CH"));

        assertThat(masked.getEmail()).isEqualTo("j*******@e******.com");
        assertThat(masked.getCountry()).isEqualTo("CH");
    }

    @Test
    @DisplayName("rebuilds a bean through its no-argument constructor and field writes")
    void rebuildsBeanWithNoArgsConstructor() {
        Banking.MutableCustomer masked = dataMask.mask(Banking.MutableCustomer.of("john.doe@example.com", "CH"));

        assertThat(masked.getEmail()).isEqualTo("j*******@e******.com");
        assertThat(masked.getCountry()).isEqualTo("CH");
    }

    @Test
    @DisplayName("masks inside Optional and arrays")
    void masksContainers() {
        record Holder(Optional<Banking.Email> email, Banking.Email[] others) {}
        Holder masked = dataMask.mask(new Holder(
                Optional.of(new Banking.Email("john.doe@example.com")),
                new Banking.Email[] {new Banking.Email("jane.roe@example.com")}));

        assertThat(masked.email()).map(Banking.Email::value).contains("j*******@e******.com");
        assertThat(masked.others()[0].value()).isEqualTo("j*******@e******.com");
    }

    @Test
    @DisplayName("preserves list order and size")
    void preservesCollectionShape() {
        record Batch(List<Banking.Email> recipients) {}
        List<Banking.Email> recipients = new ArrayList<>(
                List.of(new Banking.Email("a.one@example.com"), new Banking.Email("b.two@example.org")));

        Batch masked = dataMask.mask(new Batch(recipients));

        assertThat(masked.recipients()).hasSize(2);
        assertThat(masked.recipients().getFirst().value()).isEqualTo("a****@e******.com");
        assertThat(masked.recipients().getLast().value()).isEqualTo("b****@e******.org");
    }

    @Test
    @DisplayName("stops descending at the configured depth instead of running away")
    void enforcesDepthLimit() {
        record Link(
                Link next,
                @PII(category = PiiCategory.EMAIL) String email) {}
        Link deep = new Link(new Link(new Link(null, "c@example.com"), "b@example.com"), "a@example.com");

        DataMask shallow = DataMask.builder()
                .secret("a-test-secret-of-sufficient-length")
                .policy(MaskingPolicy.strict().withThreshold(ch.raph.datamask.api.Sensitivity.LOW))
                .build();

        assertThatCode(() -> shallow.mask(deep)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("reports what it masked to the observer")
    void notifiesObserver() {
        List<String> masked = new CopyOnWriteArrayList<>();
        DataMask observed = DataMask.builder()
                .secret("a-test-secret-of-sufficient-length")
                .observer(new MaskingObserver() {
                    @Override
                    public void onMasked(String path, PiiCategory category, MaskStrategy strategy) {
                        masked.add(path + ":" + category + ":" + strategy);
                    }
                })
                .build();

        observed.mask(new Banking.Card("4111111111111111", "123", "John Doe"));

        assertThat(masked).contains("Card.number:PAN:PAN", "Card.cvv:CARD_VERIFICATION_VALUE:REDACT");
    }

    @Test
    @DisplayName("flags PII that nobody annotated, which is the signal worth alerting on")
    void reportsUnannotatedPii() {
        List<String> detected = new CopyOnWriteArrayList<>();
        DataMask observed = DataMask.builder()
                .secret("a-test-secret-of-sufficient-length")
                .observer(new MaskingObserver() {
                    @Override
                    public void onUnannotatedPii(String path, PiiCategory category, String detector) {
                        detected.add(path + ":" + detector);
                    }
                })
                .build();

        record Ticket(String body) {}
        observed.mask(new Ticket("customer 4111111111111111 called"));

        assertThat(detected).anySatisfy(entry -> assertThat(entry).endsWith(":payment-card"));
    }

    @Test
    @DisplayName("refuses PASS_THROUGH when a masker fails, because that would disclose the value")
    void neverPassesThroughOnMaskerFailure() {
        record Exploding(@PII(masker = ThrowingMasker.class) String secret) {}
        DataMask lenient = DataMask.builder()
                .secret("a-test-secret-of-sufficient-length")
                .policy(MaskingPolicy.strict().withFailureMode(FailureMode.PASS_THROUGH))
                .build();

        assertThatThrownBy(() -> lenient.mask(new Exploding("CH9300762011623852957")))
                .isInstanceOf(MaskingException.class)
                .hasMessageContaining("would disclose");
    }

    @Test
    @DisplayName("redacts rather than propagates when a masker fails under the default policy")
    void failsClosedOnMaskerFailure() {
        record Exploding(@PII(masker = ThrowingMasker.class) String secret) {}

        Exploding masked = dataMask.mask(new Exploding("CH9300762011623852957"));

        assertThat(masked.secret()).isEqualTo("****");
    }

    public static final class ThrowingMasker implements ch.raph.datamask.api.Masker {

        @Override
        public Object mask(Object value, ch.raph.datamask.api.MaskContext context) {
            throw new IllegalStateException("deliberate failure");
        }
    }
}
