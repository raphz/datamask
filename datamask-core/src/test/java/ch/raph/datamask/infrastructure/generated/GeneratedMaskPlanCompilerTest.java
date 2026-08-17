package ch.raph.datamask.infrastructure.generated;

import static org.assertj.core.api.Assertions.assertThat;

import ch.raph.datamask.api.PiiCategory;
import ch.raph.datamask.application.MaskPlanCompiler;
import ch.raph.datamask.domain.MaskAction;
import ch.raph.datamask.domain.MaskPlan;
import ch.raph.datamask.domain.MemberPlan;
import ch.raph.datamask.domain.PiiDescriptor;
import ch.raph.datamask.domain.PolicyOverrides;
import ch.raph.datamask.infrastructure.reflect.ReflectiveMaskPlanCompiler;
import ch.raph.datamask.testdomain.Banking;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The runtime half of build-time plan generation: which compiler answers, and when it steps aside.
 *
 * <p>The plans here are hand-built rather than generated — {@code datamask-build-processor} is what
 * proves generated ones match, and it cannot be on this module's classpath without pointing the
 * dependency direction backwards. What is under test is the lookup, the fallback and the one
 * configuration that has to turn the whole thing off.
 */
@DisplayName("Generated plans are used where they exist, and reflection covers everything else")
class GeneratedMaskPlanCompilerTest {

    private final ReflectiveMaskPlanCompiler reflective = new ReflectiveMaskPlanCompiler();

    /** A deliberately wrong plan, so a test can tell which of the two compilers answered. */
    private static MaskPlan recognisable(Class<?> type) {
        return new MaskPlan(
                type,
                java.util.List.of(new MemberPlan(
                        "generated",
                        String.class,
                        target -> "generated",
                        new MaskAction.Mask(PiiDescriptor.redacting(PiiCategory.UNSPECIFIED)))),
                (original, values) -> original);
    }

    @Nested
    @DisplayName("Lookup")
    class Lookup {

        @Test
        @DisplayName("answers from the generated plan when the type has one")
        void usesTheGeneratedPlan() {
            MaskPlan generated = recognisable(Banking.Customer.class);
            MaskPlanCompiler compiler =
                    new GeneratedMaskPlanCompiler(Map.of(Banking.Customer.class, generated), reflective);

            assertThat(compiler.planFor(Banking.Customer.class)).isSameAs(generated);
        }

        @Test
        @DisplayName("falls back to reflection for a type nobody generated one for, which is what lets an "
                + "application mask a DTO the processor never saw")
        void fallsBackForUnknownTypes() {
            MaskPlanCompiler compiler = new GeneratedMaskPlanCompiler(
                    Map.of(Banking.Customer.class, recognisable(Banking.Customer.class)), reflective);

            assertThat(compiler.planFor(Banking.Card.class).members())
                    .extracting(MemberPlan::name)
                    .containsExactly("number", "cvv", "holder");
        }

        @Test
        @DisplayName("reports how many types it answers for, so an application can tell whether the processor "
                + "actually ran")
        void countsWhatItHolds() {
            GeneratedMaskPlanCompiler compiler = new GeneratedMaskPlanCompiler(
                    Map.of(Banking.Customer.class, recognisable(Banking.Customer.class)), reflective);

            assertThat(compiler.generatedPlanCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("What the default resolution does")
    class Defaults {

        @Test
        @DisplayName("answers reflectively for every type when nothing was generated, and holds no plan at all — "
                + "which is what an application without the processor is entitled to")
        void reflectiveWhenNothingIsGenerated() {
            GeneratedMaskPlanCompiler compiler = (GeneratedMaskPlanCompiler)
                    GeneratedMaskPlanCompiler.orReflective(PolicyOverrides.none(), noServicesAtAll());

            assertThat(compiler.planFor(Banking.Card.class).members())
                    .extracting(MemberPlan::name)
                    .containsExactly("number", "cvv", "holder");
            assertThat(compiler.generatedPlanCount()).isZero();
        }

        @Test
        @DisplayName("gives up the generated plan for a type an override touches: a plan resolved at compile "
                + "time cannot know about an override, and an ignored override is an unmasked value")
        void reflectiveForAnOverriddenType() {
            PolicyOverrides overrides = new PolicyOverrides(
                    Map.of(
                            Banking.Portfolio.class.getName() + "#reference",
                            PiiDescriptor.redacting(PiiCategory.CUSTOMER_ID)),
                    Map.of());

            GeneratedMaskPlanCompiler compiler =
                    (GeneratedMaskPlanCompiler) GeneratedMaskPlanCompiler.orReflective(overrides);
            compiler.planFor(Banking.Portfolio.class);

            assertThat(compiler.generatedPlanCount()).isZero();
        }

        @Test
        @DisplayName("keeps the generated plans an override does not touch, instead of turning build-time "
                + "masking off across the whole application because one DTO has one")
        void keepsUntouchedGeneratedPlans(@TempDir Path classpathEntry) throws Exception {
            ClassLoader loader = serviceLoaderFor(classpathEntry, DeclaredPlan.class, DeclaredCardPlan.class);
            PolicyOverrides overrides = new PolicyOverrides(
                    Map.of(
                            Banking.Customer.class.getName() + "#country",
                            PiiDescriptor.redacting(PiiCategory.CUSTOMER_ID)),
                    Map.of());

            MaskPlanCompiler compiler = GeneratedMaskPlanCompiler.orReflective(overrides, loader);

            // Customer is overridden, so it goes back to reflection and the override is honoured.
            assertThat(compiler.planFor(Banking.Customer.class).members())
                    .extracting(MemberPlan::name)
                    .doesNotContain("generated");
            // Card is untouched, so it keeps the plan the build produced.
            assertThat(compiler.planFor(Banking.Card.class).members())
                    .extracting(MemberPlan::name)
                    .containsExactly("generated");
        }

        @Test
        @DisplayName("and the override it would have ignored is the one that still applies")
        void theOverrideStillApplies() {
            PolicyOverrides overrides = new PolicyOverrides(
                    Map.of(
                            Banking.Portfolio.class.getName() + "#reference",
                            PiiDescriptor.redacting(PiiCategory.CUSTOMER_ID)),
                    Map.of());

            MaskPlan plan = GeneratedMaskPlanCompiler.orReflective(overrides).planFor(Banking.Portfolio.class);

            assertThat(plan.members().getFirst().action()).isInstanceOf(MaskAction.Mask.class);
        }
    }

    @Nested
    @DisplayName("Discovery")
    class Discovery {

        @Test
        @DisplayName("finds a plan through META-INF/services, which is the only wiring adding the processor to "
                + "the annotation path is supposed to need")
        void loadsFromTheServiceFile(@TempDir Path classpathEntry) throws Exception {
            Path services = classpathEntry.resolve("META-INF/services");
            Files.createDirectories(services);
            Files.writeString(services.resolve(GeneratedMaskPlan.class.getName()), DeclaredPlan.class.getName() + "\n");

            ClassLoader loader = new URLClassLoader(
                    new URL[] {classpathEntry.toUri().toURL()}, getClass().getClassLoader());
            MaskPlanCompiler compiler = GeneratedMaskPlanCompiler.orReflective(PolicyOverrides.none(), loader);

            assertThat(compiler).isInstanceOf(GeneratedMaskPlanCompiler.class);
            assertThat(compiler.planFor(Banking.Customer.class).members())
                    .extracting(MemberPlan::name)
                    .containsExactly("generated");
        }

        @Test
        @DisplayName("keeps going when one plan fails to build, because a broken optimisation must not be the "
                + "reason an application cannot start")
        void oneBrokenPlanDoesNotStopTheRest() throws Exception {
            Path classpathEntry = Files.createTempDirectory("datamask-services");
            Path services = classpathEntry.resolve("META-INF/services");
            Files.createDirectories(services);
            Files.writeString(
                    services.resolve(GeneratedMaskPlan.class.getName()),
                    BrokenPlan.class.getName() + "\n" + DeclaredPlan.class.getName() + "\n");

            ClassLoader loader = new URLClassLoader(
                    new URL[] {classpathEntry.toUri().toURL()}, getClass().getClassLoader());
            MaskPlanCompiler compiler = GeneratedMaskPlanCompiler.orReflective(PolicyOverrides.none(), loader);

            // The broken one falls back to reflection; the sound one is still answered from.
            assertThat(compiler.planFor(Banking.Card.class).members()).hasSize(3);
            assertThat(compiler.planFor(Banking.Customer.class).members())
                    .extracting(MemberPlan::name)
                    .containsExactly("generated");
        }

        @Test
        @DisplayName("starts anyway when the service file names a class that is gone, rather than letting a "
                + "stale registration take the application down")
        void aStaleServiceRegistrationDoesNotStopStartup(@TempDir Path classpathEntry) throws Exception {
            Path services = classpathEntry.resolve("META-INF/services");
            Files.createDirectories(services);
            // ServiceLoader raises ServiceConfigurationError from the iterator itself here, before
            // any provider is handed over — outside the loop body, where a guard around the call to
            // plan() could never see it. That one error used to escape and fail startup.
            Files.writeString(
                    services.resolve(GeneratedMaskPlan.class.getName()),
                    "ch.raph.datamask.generated.Vanished_MaskPlan\n");

            ClassLoader loader = new URLClassLoader(
                    new URL[] {classpathEntry.toUri().toURL()}, getClass().getClassLoader());

            GeneratedMaskPlanCompiler compiler =
                    (GeneratedMaskPlanCompiler) GeneratedMaskPlanCompiler.orReflective(PolicyOverrides.none(), loader);

            assertThat(compiler.generatedPlanCount()).isZero();
            assertThat(compiler.planFor(Banking.Card.class).members()).hasSize(3);
        }
    }

    private static ClassLoader serviceLoaderFor(Path classpathEntry, Class<?>... plans) throws Exception {
        Path services = classpathEntry.resolve("META-INF/services");
        Files.createDirectories(services);
        StringBuilder registrations = new StringBuilder();
        for (Class<?> plan : plans) {
            registrations.append(plan.getName()).append('\n');
        }
        Files.writeString(services.resolve(GeneratedMaskPlan.class.getName()), registrations.toString());
        return new URLClassLoader(
                new URL[] {classpathEntry.toUri().toURL()}, GeneratedMaskPlanCompilerTest.class.getClassLoader());
    }

    /** A second generated plan, so a test can tell one type being given up from all of them. */
    public static final class DeclaredCardPlan implements GeneratedMaskPlan {

        @Override
        public Class<?> type() {
            return Banking.Card.class;
        }

        @Override
        public MaskPlan plan() {
            return recognisable(Banking.Card.class);
        }
    }

    /** Stands in for what the processor emits. Public with a public no-argument constructor, as it must be. */
    public static final class DeclaredPlan implements GeneratedMaskPlan {

        @Override
        public Class<?> type() {
            return Banking.Customer.class;
        }

        @Override
        public MaskPlan plan() {
            return recognisable(Banking.Customer.class);
        }
    }

    /** A plan that cannot be built — a generated class compiled against a type that has since changed. */
    public static final class BrokenPlan implements GeneratedMaskPlan {

        @Override
        public Class<?> type() {
            return Banking.Card.class;
        }

        @Override
        public MaskPlan plan() {
            throw new NoSuchMethodError("Banking$Card.<init>");
        }
    }

    /** A classloader with no {@code META-INF/services} at all, so nothing is ever found through it. */
    private static ClassLoader noServicesAtAll() {
        return new ClassLoader(GeneratedMaskPlanCompilerTest.class.getClassLoader()) {
            @Override
            public java.util.Enumeration<java.net.URL> getResources(String name) {
                return java.util.Collections.emptyEnumeration();
            }
        };
    }
}
