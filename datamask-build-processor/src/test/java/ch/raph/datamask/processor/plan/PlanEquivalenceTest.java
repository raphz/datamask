package ch.raph.datamask.processor.plan;

import static org.assertj.core.api.Assertions.assertThat;

import ch.raph.datamask.application.DataMask;
import ch.raph.datamask.application.MaskPlanCompiler;
import ch.raph.datamask.domain.MaskAction;
import ch.raph.datamask.domain.MaskPlan;
import ch.raph.datamask.domain.MemberPlan;
import ch.raph.datamask.infrastructure.generated.GeneratedMaskPlanCompiler;
import ch.raph.datamask.infrastructure.reflect.ReflectiveMaskPlanCompiler;
import ch.raph.datamask.plan.testdomain.Banking;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The claim the whole module rests on: a plan worked out at compile time says exactly what the
 * reflective one says, and masks to exactly the same thing.
 *
 * <p>Two compilers deriving the same answer by different routes is a thing that decays silently. A
 * generated plan that quietly disagreed would not throw and would not look wrong — it would just
 * mask one field differently from the way the annotation says, on whichever deployment happened to
 * have the processor on its path. So the comparison is made directly, member by member, over a
 * domain that covers every shape the classification rules distinguish.
 */
@DisplayName("A generated plan and a reflective plan describe the same masking, member for member")
class PlanEquivalenceTest {

    private static final String SECRET = "test-secret-0123456789";

    private static Generation generation;
    private static Map<Class<?>, MaskPlan> generated;

    @BeforeAll
    static void generate() {
        generation = Generation.ofTestDomain();
        generated = generation.plans();
    }

    /** One member of a plan, reduced to what both compilers must agree on. */
    private record Described(String name, Class<?> declaredType, MaskAction action) {

        static List<Described> of(MaskPlan plan) {
            return plan.members().stream().map(Described::of).toList();
        }

        static Described of(MemberPlan member) {
            return new Described(member.name(), member.declaredType(), member.action());
        }
    }

    @Nested
    @DisplayName("What the plans say")
    class Structure {

        @Test
        @DisplayName("every generated plan has the same members, in the same order, with the same action as the "
                + "plan the reflective compiler derives for that class")
        void identicalToTheReflectivePlan() {
            MaskPlanCompiler reflective = new ReflectiveMaskPlanCompiler();

            assertThat(generated).isNotEmpty();
            generated.forEach((type, plan) -> assertThat(Described.of(plan))
                    .as("the generated plan for %s", type.getName())
                    .containsExactlyElementsOf(Described.of(reflective.planFor(type))));
        }

        @Test
        @DisplayName("a category that is never partially revealed comes out with keep = 0 even though the "
                + "annotation asked for three, because the generated descriptor goes through the real constructor")
        void neverPartiallyRevealedSurvivesGeneration() {
            MaskPlan plan = generated.get(Banking.Card.class);

            MaskAction cvv = plan.members().stream()
                    .filter(member -> member.name().equals("cvv"))
                    .findFirst()
                    .orElseThrow()
                    .action();

            assertThat(cvv)
                    .isInstanceOfSatisfying(
                            MaskAction.Mask.class,
                            mask -> assertThat(mask.descriptor().keep()).isZero());
        }
    }

    @Nested
    @DisplayName("Which types get a plan")
    class Coverage {

        @Test
        @DisplayName("every type that declares @PII, whatever shape it is")
        void annotatedTypes() {
            assertThat(generated.keySet())
                    .contains(
                            Banking.Email.class,
                            Banking.Customer.class,
                            Banking.Card.class,
                            Banking.Account.class,
                            Banking.Profile.class,
                            Banking.LowRisk.class,
                            Banking.Movement.class,
                            Banking.Awkward.class,
                            Banking.LegacyCustomer.class,
                            Banking.SettableCustomer.class,
                            Banking.OpenCustomer.class);
        }

        @Test
        @DisplayName("and the wrapper that declares nothing but holds one, which is the object an application "
                + "actually hands to mask()")
        void typesReachedByContainment() {
            assertThat(generated.keySet()).contains(Banking.Portfolio.class);
        }

        @Test
        @DisplayName("but not a bean whose fields are private with no setter: reflection reaches those through a "
                + "private lookup and generated source cannot, so the reflective compiler keeps them")
        void typesLeftToReflection() {
            assertThat(generated.keySet()).doesNotContain(Banking.MutableCustomer.class, Banking.Node.class);
        }

        @Test
        @DisplayName("nor the holder class itself, which owns annotated types but no annotated fields")
        void theHolderClassIsNotAType() {
            assertThat(generated.keySet()).doesNotContain(Banking.class);
        }

        @Test
        @DisplayName("and every plan that was generated is listed in META-INF/services, because a plan the "
                + "service loader cannot see is a plan that silently did nothing")
        void everyPlanIsRegistered() {
            List<String> registered = generation.serviceFile().lines().toList();

            assertThat(registered).hasSize(generated.size()).allMatch(name -> name.endsWith("_MaskPlan"));
        }
    }

    @Nested
    @DisplayName("What the plans do")
    class Behaviour {

        private final DataMask reflective = DataMask.builder()
                .secret(SECRET)
                .compiler(new ReflectiveMaskPlanCompiler())
                .build();

        private final DataMask fromGeneratedPlans = DataMask.builder()
                .secret(SECRET)
                .compiler(new GeneratedMaskPlanCompiler(generated, new ReflectiveMaskPlanCompiler()))
                .build();

        @Test
        @DisplayName("a record masks to exactly the same value either way")
        void records() {
            List<Object> fixtures = List.of(
                    new Banking.Customer(new Banking.Email("bruno@example.com"), "CH9300762011623852957", "CH"),
                    new Banking.Card("4111111111111111", "123", "Bruno Meier"),
                    new Banking.Account("CH9300762011623852957", new BigDecimal("1234.50"), "CHF"),
                    new Banking.Profile(
                            "Bruno Meier",
                            LocalDate.of(1980, 5, 17),
                            "756.1234.5678.97",
                            "+41791234567",
                            "192.168.1.24",
                            "CUST-4711",
                            "called about CH9300762011623852957",
                            "sk_live_abcdef",
                            "retail"),
                    new Banking.LowRisk("bruno@example.com"),
                    new Banking.Awkward("CH93 0076 2011 6238 5295 7", "shout me"),
                    movement());

            // Recursive rather than equals: one of these records holds an array, and a record's own
            // equals compares that by identity, which would pass for two different maskings.
            fixtures.forEach(fixture -> assertThat(fromGeneratedPlans.mask(fixture))
                    .as("masking %s", fixture.getClass().getSimpleName())
                    .usingRecursiveComparison()
                    .isEqualTo(reflective.mask(fixture)));
        }

        @Test
        @DisplayName("so does a nested graph with a collection and a map in it, where the wrapper's own plan was "
                + "generated only because it holds something annotated")
        void nestedGraph() {
            Banking.Portfolio portfolio = new Banking.Portfolio(
                    "PF-1",
                    new Banking.Customer(new Banking.Email("bruno@example.com"), "CH9300762011623852957", "CH"),
                    List.of(new Banking.Account("CH9300762011623852957", new BigDecimal("10.00"), "CHF")),
                    Map.of("main", new Banking.Card("4111111111111111", "123", "Bruno Meier")));

            assertThat(fromGeneratedPlans.mask(portfolio)).isEqualTo(reflective.mask(portfolio));
        }

        @Test
        @DisplayName("a bean rebuilt through its all-arguments constructor masks the same as one rebuilt through "
                + "an unreflected constructor handle")
        void beanWithAnAllArgumentsConstructor() {
            Banking.LegacyCustomer customer = new Banking.LegacyCustomer("bruno@example.com", "CH");

            Banking.LegacyCustomer fromPlan = fromGeneratedPlans.mask(customer);
            Banking.LegacyCustomer fromReflection = reflective.mask(customer);

            assertThat(fromPlan.getEmail()).isEqualTo(fromReflection.getEmail()).doesNotContain("bruno@example.com");
            assertThat(fromPlan.getCountry()).isEqualTo(fromReflection.getCountry());
        }

        @Test
        @DisplayName("and so does one rebuilt by a no-argument constructor followed by writes, whether those go "
                + "through setters or straight at the fields")
        void beansRebuiltByWriting() {
            Banking.SettableCustomer settable = Banking.SettableCustomer.of("bruno@example.com", "CH");
            Banking.OpenCustomer open = Banking.OpenCustomer.of("bruno@example.com", "CH");

            assertThat(fromGeneratedPlans.mask(settable).getEmail())
                    .isEqualTo(reflective.mask(settable).getEmail())
                    .doesNotContain("bruno@example.com");
            assertThat(fromGeneratedPlans.mask(open).getEmail())
                    .isEqualTo(reflective.mask(open).getEmail())
                    .doesNotContain("bruno@example.com");
        }

        @Test
        @DisplayName("a type with no generated plan is masked by the fallback, so an application adopting the "
                + "processor never finds a type it can no longer mask")
        void fallsBackForTypesWithoutAPlan() {
            Banking.MutableCustomer customer = Banking.MutableCustomer.of("bruno@example.com", "CH");

            assertThat(fromGeneratedPlans.mask(customer).getEmail())
                    .isEqualTo(reflective.mask(customer).getEmail())
                    .doesNotContain("bruno@example.com");
        }

        private Banking.Movement movement() {
            return new Banking.Movement(
                    "0835-1234567-01",
                    7,
                    1_700_000_000L,
                    'D',
                    false,
                    Banking.Channel.MOBILE,
                    new BigDecimal("42.00"),
                    LocalDate.of(2026, 3, 1),
                    UUID.fromString("6c84fb90-12c4-11e1-840d-7b25c5ee775a"),
                    "salary",
                    "note about bruno@example.com",
                    new String[] {"payroll", "bruno@example.com"},
                    List.of("a", "bruno@example.com"),
                    Optional.of("bruno@example.com"),
                    "bruno@example.com",
                    null);
        }
    }
}
