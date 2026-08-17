package ch.raph.datamask.processor.plan;

import static org.assertj.core.api.Assertions.assertThat;

import ch.raph.datamask.domain.MemberPlan;
import ch.raph.datamask.domain.PolicyOverrides;
import ch.raph.datamask.infrastructure.generated.GeneratedMaskPlanCompiler;
import ch.raph.datamask.plan.downstream.Envelope;
import ch.raph.datamask.plan.upstream.Contact;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * How a generated plan is found again, which is the half of this module that has nothing to do with
 * what the plan says.
 *
 * <p>A plan nobody can find does not fail: masking quietly goes back to reflection for that type,
 * produces the same output, and says nothing. Both cases here are ones where that used to happen to
 * exactly the type an application masks most — the wrapper that declares no annotation of its own
 * and is the object handed to {@code mask()}.
 */
@DisplayName("A generated plan is found by name, one type at a time")
class PlanDiscoveryTest {

    @Nested
    @DisplayName("An incremental build")
    class IncrementalBuilds {

        /**
         * The compilation that follows a one-file edit. Under a shared {@code META-INF/services}
         * index this was where plans disappeared: the index cannot be appended to, so the second
         * compilation rewrote it with only what it had just seen, and every type left out went back
         * to reflection with nothing to notice it by.
         */
        @Test
        @DisplayName("keeps the plans it generated earlier, although the compilation that followed only saw one "
                + "of the two source files")
        void doesNotLoseThePlansOfSourcesThatDidNotChange(@TempDir Path workingDirectory) {
            Generation.of(workingDirectory, Generation.UPSTREAM, Generation.DOWNSTREAM);
            Generation second = Generation.of(workingDirectory, Generation.DOWNSTREAM);

            GeneratedMaskPlanCompiler compiler = (GeneratedMaskPlanCompiler)
                    GeneratedMaskPlanCompiler.orReflective(PolicyOverrides.none(), second.classLoader());
            compiler.planFor(Contact.class);
            compiler.planFor(Envelope.class);

            assertThat(compiler.generatedPlanCount())
                    .as("both plans are on the classpath, and both have to be answered from")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("writes no index for an incremental build to rewrite, which is what lets the processor "
                + "declare itself isolating to Gradle")
        void writesNoServiceFile(@TempDir Path workingDirectory) {
            Generation generation = Generation.of(workingDirectory, Generation.UPSTREAM, Generation.DOWNSTREAM);

            assertThat(generation.sources()).isNotEmpty();
            assertThat(generation.serviceFile()).isEmpty();
        }
    }

    @Nested
    @DisplayName("A type whose annotated member comes from a dependency")
    class AcrossModules {

        /**
         * Domain types in one module and the wrapper that holds them in another is the ordinary
         * layout, and the wrapper used to get no plan at all: the annotated type was not in the
         * compilation, so nothing in it was ever asked whether it declared masking.
         */
        @Test
        @DisplayName("gets a plan, because the annotation is read off the dependency's class file")
        void wrappersOverAnnotatedTypesFromAJarAreStillPlanned(@TempDir Path workingDirectory) {
            Generation generation = Generation.of(workingDirectory, Generation.DOWNSTREAM);

            assertThat(generation.sources()).containsKey("ch.raph.datamask.plan.downstream.Envelope_MaskPlan");
        }

        @Test
        @DisplayName("and the engine answers from that plan, describing the wrapper member for member the way "
                + "the reflective compiler would have")
        void andTheEngineAnswersFromIt(@TempDir Path workingDirectory) {
            Generation generation = Generation.of(workingDirectory, Generation.DOWNSTREAM);

            GeneratedMaskPlanCompiler compiler = (GeneratedMaskPlanCompiler)
                    GeneratedMaskPlanCompiler.orReflective(PolicyOverrides.none(), generation.classLoader());

            assertThat(compiler.planFor(Envelope.class).members())
                    .extracting(MemberPlan::name)
                    .containsExactly("reference", "contact");
            assertThat(compiler.generatedPlanCount())
                    .as("the plan has to be the generated one, not the reflective fallback")
                    .isEqualTo(1);
        }
    }
}
