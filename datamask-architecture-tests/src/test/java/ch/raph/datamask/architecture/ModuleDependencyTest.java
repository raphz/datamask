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
    private static final String PROCESSOR = ROOT + ".processor..";
    private static final String SPRING = ROOT + ".spring..";
    private static final String BENCHMARKS = ROOT + ".benchmarks..";

    /** {@code javax} covers the parts of the platform that never moved out of it, such as {@code javax.crypto}. */
    private static final List<String> JDK = List.of("java..", "javax..");

    /**
     * JSpecify's nullness annotations, allowed in every module including datamask-api — whose rule is
     * otherwise "the JDK and nothing else". Every package carries a {@code @NullMarked}
     * {@code package-info}, and that annotation has runtime retention, so {@code org.jspecify.annotations}
     * is in the bytecode of every module whether or not any signature mentions it.
     *
     * <p>This does not weaken the guarantee the datamask-api rule exists for. JSpecify is annotations and
     * nothing else, it is {@code compileOnly} in the conventions so it appears in no published POM, and
     * reflection skips an annotation whose type is absent — so datamask-api still puts nothing on an
     * application's runtime classpath. What the allowance buys is the nullness contract stated in code
     * where a checker can hold the library to it, instead of in prose where only a reader can.
     */
    private static final List<String> JSPECIFY = List.of("org.jspecify..");

    /** What every module may depend on regardless of its own rule: the platform, plus the nullness annotations. */
    private static final List<String> BASELINE =
            Stream.concat(JDK.stream(), JSPECIFY.stream()).toList();

    /** The four packages that make up datamask-api and datamask-core. */
    private static final List<String> CORE = List.of(API, DOMAIN, APPLICATION, INFRASTRUCTURE);

    /**
     * The modules that have a rule of their own above instead of a row in {@link #integrations()},
     * named as this test's rules name modules.
     */
    private static final List<String> MODULES_WITH_THEIR_OWN_RULE =
            List.of("api", "domain", "application", "infrastructure", "processor", "spring", "benchmarks");

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
                // SLF4J is how the interceptor reports a record it dropped: Kafka swallows an exception
                // thrown from onSend, so a log line is the only way that failure is ever visible.
                new Integration("kafka", List.of("org.apache.kafka..", "org.slf4j..")),
                // The PostgreSQL driver is optional at runtime and compileOnly in the module, but the
                // bytecode still refers to it, so it belongs in the allowance. SLF4J is the facade the
                // statement logger writes through.
                new Integration("jdbc", List.of("org.postgresql..", "org.slf4j..")),
                new Integration("log4j2", List.of("org.apache.logging.log4j..")),
                // logback-classic and logback-core both ship under ch.qos.logback. SLF4J is where
                // ILoggingEvent's own marker, key-value and message-formatting types come from.
                // logstash-logback-encoder is optional at runtime and compileOnly in the module, but
                // the bytecode of the one class that rebuilds its appending markers still refers to
                // it — and it earns the allowance: those markers are how that encoder ships whole
                // objects into the JSON, so leaving them unmasked would be the module's largest leak.
                new Integration("logback", List.of("ch.qos.logback..", "org.slf4j..", "net.logstash.logback..")));
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
        onlyDependOn(List.of(API), and(BASELINE, API));
    }

    @Test
    @DisplayName("the domain knows only the annotations, never the use cases or the adapters, which is what "
            + "keeps the masking vocabulary independent of how masking is carried out")
    void domainDependsInwardsOnly() {
        onlyDependOn(List.of(DOMAIN), and(BASELINE, API, DOMAIN));
    }

    @Test
    @DisplayName("datamask-core depends on no third-party library and on no integration module, so putting "
            + "an integration on the classpath can never change what the engine itself resolves")
    void coreIsFrameworkFree() {
        // application -> infrastructure is deliberate and stays inside the core: DataMask.Builder and
        // MaskerRegistry are the composition root, and wiring the default maskers, detectors, key and
        // vault is precisely their job. What this rule forbids is the core reaching outside itself.
        onlyDependOn(List.of(DOMAIN, APPLICATION, INFRASTRUCTURE), and(BASELINE, CORE));
    }

    @Test
    @DisplayName("the annotation processor reads the annotations and nothing else, so the compile-time "
            + "check never drags the masking engine onto an application's compile classpath")
    void processorDependsOnTheAnnotationsOnly() {
        // Stricter than an integration row would be: the processor works on javax.lang.model mirrors of
        // @PII, never on the runtime types, so domain and application stay out of the allowance.
        onlyDependOn(List.of(PROCESSOR), and(BASELINE, API, PROCESSOR));
    }

    @Test
    @DisplayName("the Spring auto-configuration is the one module allowed to see several integrations at "
            + "once, because deciding what exists is exactly what a composition root is for")
    void springAutoConfigurationSeesTheIntegrationsItWires() {
        // Wider than an integration row on purpose, and the width is the design: this module wires the
        // engine into whichever integrations an application put on its classpath, so it has to name
        // them. Two limits still hold. It stays out of `infrastructure` — it configures masking, it does
        // not implement any — which is why the key is set through DataMask.Builder.secret(String) and a
        // MaskKey never appears here. And an integration is still forbidden from reaching back: nothing
        // in this list may be imported the other way round.
        List<String> integrationPackages =
                integrations().stream().map(Integration::basePackage).toList();
        List<String> allowed = and(
                and(BASELINE, API, DOMAIN, APPLICATION, SPRING),
                and(
                        integrationPackages,
                        // Boot itself, the SLF4J facade the startup warnings go through, Micrometer for
                        // the observer, and Jackson for the module type the Jackson auto-configuration
                        // declares as a bean.
                        List.of("org.springframework..", "org.slf4j..", "io.micrometer..", "tools.jackson..")));
        onlyDependOn(List.of(SPRING), allowed);
    }

    @Test
    @DisplayName("the benchmarks see both logging integrations and reach into infrastructure, which is what "
            + "measuring costs, and they are held to that list so the cost of a measurement stays visible")
    void benchmarksMeasureTheLibraryAndNothingElse() {
        // Not a row in integrations(): datamask-benchmarks is not a framework integration, it is
        // measurement code, and an integration row would be both too narrow and the wrong shape.
        //
        // Wider than any integration on two counts, and both are the point rather than a concession.
        // It names *two* integrations at once, because the headline number is logback's masking
        // appender against the appender underneath it and the same question has to be asked of
        // log4j2 in the same run to be comparable. And it reaches into `infrastructure`, which every
        // integration is forbidden from doing, because measuring ReflectiveMaskPlanCompiler against
        // GeneratedMaskPlanCompiler means naming both adapters — that comparison is the whole reason
        // datamask-build-processor exists, and there is no way to state it through `application`.
        //
        // What makes the width safe is that this module is never published. It applies
        // datamask.java-base-conventions, so it has no route to Maven Central and no application can
        // depend on it, which means none of this coupling can reach anyone. The rule is still worth
        // having: it holds the benchmarks to DataMask's own packages plus JMH and the two logging
        // frameworks, so a benchmark cannot quietly grow a dependency on something else and start
        // measuring it. Adding a package here should mean a new thing is being measured.
        List<String> allowed = and(
                and(and(BASELINE, CORE), BENCHMARKS),
                List.of(
                        // The harness, and the runners its annotation processor generates beside
                        // each benchmark class.
                        "org.openjdk.jmh..",
                        // The two integrations under measurement, and the frameworks whose event
                        // types the benchmarks have to build in order to have something to measure.
                        ROOT + ".logback..",
                        ROOT + ".log4j2..",
                        "ch.qos.logback..",
                        "org.slf4j..",
                        "org.apache.logging.log4j..",
                        // The JDBC wrapper, whose result-set proxy is measured against an unwrapped
                        // result set. java.sql is already in the JDK baseline; what this adds is the
                        // module. The measurement is the reason MaskingDataSource has an escape
                        // hatch at all, so the two belong in the same build.
                        ROOT + ".jdbc.."));
        onlyDependOn(List.of(BENCHMARKS), allowed);
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
                            and(BASELINE, API, DOMAIN, APPLICATION, integration.basePackage()),
                            integration.frameworkPackages());
                    onlyDependOn(List.of(integration.basePackage()), allowed);
                }));
    }

    @Test
    @DisplayName("every module that has code is covered by one of the rules above, so implementing one of "
            + "the planned modules cannot silently opt it out of the architecture check")
    void everyModuleIsCoveredByARule() {
        List<String> covered = Stream.concat(
                        MODULES_WITH_THEIR_OWN_RULE.stream(),
                        integrations().stream().map(Integration::module))
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
                .as("a module was implemented without giving it a row in ModuleDependencyTest.integrations() "
                        + "or a rule of its own")
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
