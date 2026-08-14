package ch.raph.datamask.processor.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What the emitted source is allowed to contain.
 *
 * <p>{@code PlanEquivalenceTest} proves the generated plans behave like the reflective ones. That
 * would still be true of a generated plan that reflected internally — and the reason this module
 * exists is that it must not. So the output is read as text and held to the one property the
 * behaviour cannot demonstrate.
 */
@DisplayName("The generated source reaches every member directly, and reflects over nothing")
class GeneratedSourceTest {

    private static Map<String, String> sources;

    @BeforeAll
    static void generate() {
        sources = Generation.ofTestDomain().sources();
    }

    @Nested
    @DisplayName("No reflection")
    class NoReflection {

        /**
         * The four routes back into reflection, named as they would appear in source. A generated
         * plan that used any of them would keep the correctness and lose the point: it would still
         * need reachability metadata in a native image, and it would still fail inside a module that
         * opens nothing.
         */
        private final List<String> forbidden =
                List.of("java.lang.reflect", "MethodHandle", "Class.forName", "setAccessible");

        @Test
        @DisplayName("nothing generated mentions reflection, a method handle, a class lookup by name, or "
                + "forcing access")
        void noneOfTheRoutesBack() {
            assertThat(sources).isNotEmpty();
            sources.forEach((name, source) -> assertThat(source)
                    .as("the generated source of %s", name)
                    .doesNotContain(forbidden.toArray(CharSequence[]::new)));
        }
    }

    @Nested
    @DisplayName("How members are reached")
    class Access {

        @Test
        @DisplayName("a record component through its accessor")
        void recordAccessor() {
            assertThat(sourceOf("Banking_Customer_MaskPlan"))
                    .contains("((ch.raph.datamask.plan.testdomain.Banking.Customer) target).email()");
        }

        @Test
        @DisplayName("a package-private field straight, because a getter may compute and a field cannot")
        void visibleFieldDirectly() {
            assertThat(sourceOf("Banking_OpenCustomer_MaskPlan"))
                    .contains("((ch.raph.datamask.plan.testdomain.Banking.OpenCustomer) target).email")
                    .doesNotContain("getEmail()");
        }

        @Test
        @DisplayName("a private field through its getter, which is the only way left")
        void privateFieldThroughItsGetter() {
            assertThat(sourceOf("Banking_LegacyCustomer_MaskPlan"))
                    .contains("((ch.raph.datamask.plan.testdomain.Banking.LegacyCustomer) target).getEmail()");
        }
    }

    @Nested
    @DisplayName("How copies are built")
    class Rebuilding {

        @Test
        @DisplayName("a record and an all-arguments bean through a direct constructor call")
        void directConstructor() {
            assertThat(sourceOf("Banking_Customer_MaskPlan"))
                    .contains("new ch.raph.datamask.plan.testdomain.Banking.Customer(");
            assertThat(sourceOf("Banking_LegacyCustomer_MaskPlan"))
                    .contains("new ch.raph.datamask.plan.testdomain.Banking.LegacyCustomer(");
        }

        @Test
        @DisplayName("a bean with only a no-argument constructor through its setters")
        void noArgumentsThenSetters() {
            assertThat(sourceOf("Banking_SettableCustomer_MaskPlan"))
                    .contains("copy.setEmail((java.lang.String) values[0]);");
        }

        @Test
        @DisplayName("and one with reachable fields by assigning them")
        void noArgumentsThenAssignment() {
            assertThat(sourceOf("Banking_OpenCustomer_MaskPlan"))
                    .contains("copy.email = (java.lang.String) values[0];");
        }

        @Test
        @DisplayName("a primitive is cast to its box, so a wrong value is a ClassCastException naming both types "
                + "rather than an unboxing NullPointerException naming neither")
        void primitivesCastToTheirBox() {
            assertThat(sourceOf("Banking_Movement_MaskPlan"))
                    .contains("(java.lang.Integer) values[1]")
                    .contains("(java.lang.Character) values[3]")
                    .contains("(java.lang.Boolean) values[4]");
        }
    }

    @Nested
    @DisplayName("How descriptors are written")
    class Descriptors {

        @Test
        @DisplayName("through the real constructor, so the compact one still forces keep = 0 on a category that "
                + "is never partially revealed — emitting resolved fields would have written keep = 3")
        void throughTheRealConstructor() {
            assertThat(sourceOf("Banking_Card_MaskPlan"))
                    .contains("new ch.raph.datamask.domain.PiiDescriptor(")
                    // The annotation's own keep = 3 is written out verbatim, and the constructor is
                    // what turns it into 0. PlanEquivalenceTest asserts the value that comes back.
                    .containsSubsequence("ch.raph.datamask.api.PiiCategory.CARD_VERIFICATION_VALUE", "3,");
        }

        @Test
        @DisplayName("a bare @PII resolves at compile time to what its type declares, exactly as the reflective "
                + "compiler resolves it at runtime")
        void bareAnnotationsDeferToTheirType() {
            assertThat(sourceOf("Banking_Customer_MaskPlan")).contains("ch.raph.datamask.api.PiiCategory.EMAIL");
        }

        @Test
        @DisplayName("quotes, backslashes and control characters in an attribute come back out as escapes rather "
                + "than as source that does not compile")
        void awkwardAttributesAreEscaped() {
            assertThat(sourceOf("Banking_Awkward_MaskPlan"))
                    .contains("'#'")
                    .contains("\"n/a \\\"unknown\\\"\\\\\"")
                    .contains("\"audit\\ttrail\\nrecord\"");
        }

        @Test
        @DisplayName("a custom masker is named as a class literal, not looked up by name")
        void customMaskerAsAClassLiteral() {
            assertThat(sourceOf("Banking_Awkward_MaskPlan"))
                    .contains("ch.raph.datamask.plan.testdomain.Banking.Shout.class");
        }
    }

    private static String sourceOf(String simpleName) {
        return sources.entrySet().stream()
                .filter(entry -> entry.getKey().endsWith("." + simpleName))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no plan was generated for " + simpleName + "; generated: " + sources.keySet()));
    }
}
