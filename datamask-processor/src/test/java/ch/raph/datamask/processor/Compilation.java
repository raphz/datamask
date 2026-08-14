package ch.raph.datamask.processor;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

/**
 * Runs {@link PiiProcessor} over sources held in memory and collects what it said.
 *
 * <p>{@code -proc:only} keeps the compilation to parsing, symbol entry and annotation processing:
 * no class files, no temporary directory, and no diagnostics from javac's own analysis to filter
 * out of the assertions.
 */
final class Compilation {

    private final List<String> options =
            new ArrayList<>(List.of("-proc:only", "-classpath", System.getProperty("java.class.path")));
    private final List<JavaFileObject> sources = new ArrayList<>();

    static Compilation of(JavaFileObject... sources) {
        Compilation compilation = new Compilation();
        compilation.sources.addAll(Arrays.asList(sources));
        return compilation;
    }

    static JavaFileObject source(String name, String code) {
        return new InMemorySource(name, code);
    }

    Compilation withOption(String option) {
        options.add(option);
        return this;
    }

    Result run() {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        JavaCompiler.CompilationTask task = compiler.getTask(null, null, collector, options, null, sources);
        task.setProcessors(List.of(new PiiProcessor()));
        task.call();
        return new Result(collector.getDiagnostics());
    }

    /** What the processor reported, split by severity; javac's own notes are of no interest here. */
    record Result(List<Diagnostic<? extends JavaFileObject>> diagnostics) {

        List<String> errors() {
            return messagesOf(Diagnostic.Kind.ERROR);
        }

        List<String> warnings() {
            return messagesOf(Diagnostic.Kind.WARNING, Diagnostic.Kind.MANDATORY_WARNING);
        }

        List<String> all() {
            List<String> messages = new ArrayList<>(errors());
            messages.addAll(warnings());
            return messages;
        }

        private List<String> messagesOf(Diagnostic.Kind... kinds) {
            List<Diagnostic.Kind> wanted = Arrays.asList(kinds);
            return diagnostics.stream()
                    .filter(diagnostic -> wanted.contains(diagnostic.getKind()))
                    .map(diagnostic -> diagnostic.getMessage(null))
                    .filter(message -> message.startsWith("datamask: "))
                    .toList();
        }
    }

    private static final class InMemorySource extends SimpleJavaFileObject {

        private final String code;

        private InMemorySource(String name, String code) {
            super(URI.create("string:///" + name.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }
}
