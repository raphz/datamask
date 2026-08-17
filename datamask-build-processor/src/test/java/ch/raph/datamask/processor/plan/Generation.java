package ch.raph.datamask.processor.plan;

import static org.assertj.core.api.Assertions.assertThat;

import ch.raph.datamask.domain.MaskPlan;
import ch.raph.datamask.infrastructure.generated.GeneratedMaskPlan;
import ch.raph.datamask.plan.testdomain.PlanLoader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Compiles a domain with {@link MaskPlanProcessor} attached and hands back what it produced.
 *
 * <p>Real files rather than sources built up in a string: the domain compiled here is the same file
 * Gradle already compiled onto the test classpath, so a plan generated here and a plan the
 * reflective compiler derives at runtime describe the very same classes. Comparing anything less
 * than that would compare two things nobody runs.
 *
 * <p>The generated classes are then defined into the domain's own package rather than loaded
 * through a child classloader — see {@link PlanLoader} for why the difference matters. The
 * compilation itself is real: it fails the test on any error, which is what keeps the fully
 * qualified names in {@code Names} honest.
 */
final class Generation {

    static final String TEST_DOMAIN = "src/test/java/ch/raph/datamask/plan/testdomain/Banking.java";

    /** Two modules' worth of domain: the annotated type, and the wrapper that only holds it. */
    static final String UPSTREAM = "src/test/java/ch/raph/datamask/plan/upstream/Contact.java";

    static final String DOWNSTREAM = "src/test/java/ch/raph/datamask/plan/downstream/Envelope.java";

    private static Generation testDomain;

    private final Path output;
    private final Map<String, String> sources;
    private Map<Class<?>, MaskPlan> plans;

    private Generation(Path output, Map<String, String> sources) {
        this.output = output;
        this.sources = sources;
    }

    /**
     * The test domain, generated once for the whole test run.
     *
     * <p>Shared rather than generated per test class because {@link PlanLoader} defines the plans
     * into a real package of a real classloader, and a classloader defines a name once. Two test
     * classes each generating their own copy is a {@code LinkageError}, not two independent runs.
     */
    static synchronized Generation ofTestDomain() {
        if (testDomain == null) {
            try {
                Path workingDirectory = Files.createTempDirectory("datamask-plans");
                Runtime.getRuntime().addShutdownHook(new Thread(() -> delete(workingDirectory)));
                testDomain = of(workingDirectory, TEST_DOMAIN);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return testDomain;
    }

    /**
     * Runs the processor over the given source files, failing the test on any compile error.
     *
     * <p>Compiling into a working directory that already holds output is deliberate and is how the
     * incremental case is reproduced: javac writes the files it was given and leaves everything else
     * where it is, which is what Gradle's incremental compilation does to a build directory.
     */
    static Generation of(Path workingDirectory, String... sourceFiles) {
        List<Path> paths = Stream.of(sourceFiles).map(Path::of).toList();
        paths.forEach(path -> assertThat(path)
                .as(
                        "the test domain has to be readable from the working directory, %s",
                        Path.of("").toAbsolutePath())
                .exists());

        try {
            Path output = Files.createDirectories(workingDirectory.resolve("classes"));
            Path generated = Files.createDirectories(workingDirectory.resolve("generated"));

            compile(paths, output, generated);
            return new Generation(output, readGenerated(generated));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void compile(List<Path> sources, Path output, Path generated) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, null)) {
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null,
                    files,
                    diagnostics,
                    List.of(
                            "-classpath",
                            System.getProperty("java.class.path"),
                            "-d",
                            output.toString(),
                            "-s",
                            generated.toString(),
                            "-parameters"),
                    null,
                    files.getJavaFileObjectsFromPaths(sources));
            task.setProcessors(List.of(new MaskPlanProcessor()));
            boolean succeeded = task.call();

            assertThat(errorsIn(diagnostics))
                    .as("the generated source has to compile against the real datamask-core")
                    .isEmpty();
            assertThat(succeeded)
                    .as("the domain and its generated plans have to compile")
                    .isTrue();
        }
    }

    /**
     * The plans the runtime would find, defined into the domain's own package.
     *
     * <p>Only ever called for the shared test domain: {@link PlanLoader} can define a class into one
     * package, its own, which is exactly what makes package-private access behave the way it does in
     * a real build.
     */
    Map<Class<?>, MaskPlan> plans() {
        if (plans == null) {
            try {
                plans = load(sources.keySet(), output);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return plans;
    }

    /** Generated class name to the source that was written for it. */
    Map<String, String> sources() {
        return sources;
    }

    /** Where the compiled domain and its plans landed, as an application's classpath would see them. */
    ClassLoader classLoader() {
        try {
            return new URLClassLoader(new URL[] {output.toUri().toURL()}, Generation.class.getClassLoader());
        } catch (java.net.MalformedURLException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * What a {@code META-INF/services} registration of the generated plans would say, which after
     * this processor stopped writing one is nothing at all. Kept so a test can hold that line: the
     * file is the shared state that made an incremental build drop plans.
     */
    String serviceFile() {
        Path file = output.resolve("META-INF/services/ch.raph.datamask.infrastructure.generated.GeneratedMaskPlan");
        try {
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<Class<?>, MaskPlan> load(Iterable<String> names, Path output) throws IOException {
        Map<Class<?>, MaskPlan> plans = new LinkedHashMap<>();
        for (String name : names) {
            byte[] bytecode = Files.readAllBytes(output.resolve(name.replace('.', '/') + ".class"));
            try {
                Object instance =
                        PlanLoader.define(bytecode).getDeclaredConstructor().newInstance();
                GeneratedMaskPlan generated = (GeneratedMaskPlan) instance;
                plans.put(generated.type(), generated.plan());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("could not instantiate the generated plan " + name, e);
            }
        }
        return plans;
    }

    private static Map<String, String> readGenerated(Path root) throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file :
                    files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String name = root.relativize(file)
                        .toString()
                        .replace(java.io.File.separatorChar, '.')
                        .replaceFirst("\\.java$", "");
                sources.put(name, Files.readString(file, StandardCharsets.UTF_8));
            }
        }
        return sources;
    }

    private static void delete(Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder())
                    .forEach(path -> path.toFile().delete());
        } catch (IOException ignored) {
            // A temporary directory left behind is not worth failing a build over.
        }
    }

    private static List<String> errorsIn(DiagnosticCollector<JavaFileObject> diagnostics) {
        List<String> errors = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                errors.add(diagnostic.toString());
            }
        }
        return errors;
    }
}
