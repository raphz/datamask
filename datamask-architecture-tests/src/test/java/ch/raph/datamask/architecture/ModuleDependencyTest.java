package ch.raph.datamask.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Asserts the dependency direction the library is designed around. Every module is on this module's
 * test classpath, so the rules are stated once here rather than module by module.
 *
 * <p>Two of these are worth a build failure rather than a code review. A leak of
 * {@code datamask-core} into a framework package would put a third-party version on the critical
 * path of a security library, and a dependency between two integration modules would make an
 * application that wants one of them pull in the other's framework as well.
 */
@DisplayName("The dependencies between DataMask's modules point the way the design says they do")
class ModuleDependencyTest {

    private static final String ROOT = "ch.raph.datamask";

    private static final String API = ROOT + ".api..";
    private static final String DOMAIN = ROOT + ".domain..";
    private static final String APPLICATION = ROOT + ".application..";
    private static final String INFRASTRUCTURE = ROOT + ".infrastructure..";

    /** {@code javax} covers the parts of the platform that never moved out of it, such as {@code javax.crypto}. */
    private static final List<String> JDK = List.of("java..", "javax..");

    /** The four packages that make up datamask-api and datamask-core. */
    private static final List<String> CORE = List.of(API, DOMAIN, APPLICATION, INFRASTRUCTURE);

    /** The core packages, named as this test's rules name modules. */
    private static final List<String> CORE_MODULES = List.of("api", "domain", "application", "infrastructure");

    /** Test classes are excluded, so every rule describes the bytecode that actually ships. */
    private static final JavaClasses LIBRARY = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(ROOT);

    /**
     * An integration module and the framework packages it is allowed to reach for. A module earns a
     * row when it gets its first class — a row for a package with nothing in it would fail as a rule
     * that matched no classes. {@link #everyModuleIsCoveredByARule()} is what stops a newly
     * implemented module from quietly skipping its row.
     */
    private static List<Integration> integrations() {
        return List.of(
                new Integration("jackson", List.of("tools.jackson..")),
                // The PostgreSQL driver is optional at runtime and compileOnly in the module, but the
                // bytecode still refers to it, so it belongs in the allowance. SLF4J is the facade the
                // statement logger writes through.
                new Integration("jdbc", List.of("org.postgresql..", "org.slf4j..")),
                new Integration("log4j2", List.of("org.apache.logging.log4j..")));
    }

    private record Integration(String module, List<String> frameworkPackages) {

        String basePackage() {
            return ROOT + "." + module + "..";
        }
    }

    @Test
    @DisplayName("datamask-api depends on nothing but the JDK, so a domain class can carry @PII without "
            + "dragging the masking engine into its build")
    void apiDependsOnNothing() {
        onlyDependOn(List.of(API), and(JDK, API));
    }

    @Test
    @DisplayName("the domain knows only the annotations, never the use cases or the adapters, which is what "
            + "keeps the masking vocabulary independent of how masking is carried out")
    void domainDependsInwardsOnly() {
        onlyDependOn(List.of(DOMAIN), and(JDK, API, DOMAIN));
    }

    @Test
    @DisplayName("datamask-core depends on no third-party library and on no integration module, so putting "
            + "an integration on the classpath can never change what the engine itself resolves")
    void coreIsFrameworkFree() {
        // application -> infrastructure is deliberate and stays inside the core: DataMask.Builder and
        // MaskerRegistry are the composition root, and wiring the default maskers, detectors, key and
        // vault is precisely their job. What this rule forbids is the core reaching outside itself.
        onlyDependOn(List.of(DOMAIN, APPLICATION, INFRASTRUCTURE), and(JDK, CORE));
    }

    @TestFactory
    @DisplayName("an integration module depends on the core and on its own framework, and on no other "
            + "integration — so an application that wants one of them does not inherit another's framework")
    Stream<DynamicTest> integrationDependsOnItsOwnFrameworkOnly() {
        return integrations().stream()
                .map(integration -> dynamicTest("datamask-" + integration.module(), () -> {
                    // infrastructure is left out on purpose. An integration is handed a MaskingEngine and
                    // speaks api, domain and application types; reaching into an adapter would couple it to a
                    // masking implementation it has no business knowing about.
                    List<String> allowed = and(
                            and(JDK, API, DOMAIN, APPLICATION, integration.basePackage()),
                            integration.frameworkPackages());
                    onlyDependOn(List.of(integration.basePackage()), allowed);
                }));
    }

    @Test
    @DisplayName("every module that has code is covered by one of the rules above, so implementing one of "
            + "the planned modules cannot silently opt it out of the architecture check")
    void everyModuleIsCoveredByARule() {
        List<String> covered = Stream.concat(
                        CORE_MODULES.stream(), integrations().stream().map(Integration::module))
                .toList();

        // The segment straight after ch.raph.datamask: `infrastructure.masker` counts as
        // `infrastructure`, `jdbc` as `jdbc`.
        List<String> present = LIBRARY.stream()
                .map(JavaClass::getPackageName)
                .filter(name -> name.startsWith(ROOT + "."))
                .map(name -> name.substring(ROOT.length() + 1).split("\\.")[0])
                .distinct()
                .toList();

        assertThat(present)
                .as("a module was implemented without adding it to ModuleDependencyTest.integrations()")
                .isSubsetOf(covered);
    }

    private static void onlyDependOn(List<String> subjectPackages, List<String> allowedPackages) {
        classes()
                .that()
                .resideInAnyPackage(subjectPackages.toArray(String[]::new))
                .should()
                .onlyDependOnClassesThat()
                .resideInAnyPackage(allowedPackages.toArray(String[]::new))
                .check(LIBRARY);
    }

    private static List<String> and(List<String> packages, String... more) {
        return Stream.concat(packages.stream(), Arrays.stream(more)).toList();
    }

    private static List<String> and(List<String> packages, List<String> more) {
        return Stream.concat(packages.stream(), more.stream()).toList();
    }
}
