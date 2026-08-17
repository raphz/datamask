package ch.raph.datamask.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.diagnostics.FailureAnalyzer;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.core.type.filter.AnnotationTypeFilter;

/**
 * Guards the two files that decide whether any of this module runs at all.
 *
 * <p>Worth its own test because both fail silently. An auto-configuration missing from the imports
 * file is simply never loaded — no error, no warning, and every test that constructs its own
 * {@code ApplicationContextRunner} still passes, because a runner names the configurations itself.
 * The application just quietly stops masking whatever that integration covered.
 */
@DisplayName("Every auto-configuration is registered where Spring Boot looks for it")
class RegistrationTest {

    private static final String IMPORTS =
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    @Test
    @DisplayName("lists every @AutoConfiguration in the package, so adding one for a new integration "
            + "cannot silently do nothing")
    void everyAutoConfigurationIsImported() throws IOException {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(AutoConfiguration.class));

        Set<String> declared = scanner.findCandidateComponents("ch.raph.datamask.spring").stream()
                .map(BeanDefinition::getBeanClassName)
                .collect(Collectors.toSet());

        assertThat(imports()).containsExactlyInAnyOrderElementsOf(declared);
    }

    @Test
    @DisplayName("names classes that exist, because a typo in this file is invisible until an application starts")
    void everyImportResolves() throws IOException, ClassNotFoundException {
        for (String name : imports()) {
            assertThat(Class.forName(name)).hasAnnotation(AutoConfiguration.class);
        }
    }

    @Test
    @DisplayName("registers both secret failure analyzers, which are the whole difference between a readable "
            + "startup message and a bean-creation stack trace")
    void failureAnalyzerIsRegistered() {
        // Boot's own analyzers want a bean factory this test has no reason to build, so their
        // instantiation failures are ignored; ours needs nothing and has to appear regardless.
        List<FailureAnalyzer> analyzers = SpringFactoriesLoader.forDefaultResourceLocation()
                .load(FailureAnalyzer.class, SpringFactoriesLoader.FailureHandler.handleMessage((message, failure) -> {
                    /* another module's analyzer, not ours */
                }));

        assertThat(analyzers)
                .hasAtLeastOneElementOfType(MissingMaskSecretFailureAnalyzer.class)
                .hasAtLeastOneElementOfType(ShortMaskSecretFailureAnalyzer.class);
    }

    private static List<String> imports() throws IOException {
        try (InputStream in = RegistrationTest.class.getClassLoader().getResourceAsStream(IMPORTS)) {
            assertThat(in).as("the imports file is on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
        }
    }
}
