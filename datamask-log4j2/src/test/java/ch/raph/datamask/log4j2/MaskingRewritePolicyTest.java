package ch.raph.datamask.log4j2;

import static org.assertj.core.api.Assertions.assertThat;

import ch.raph.datamask.application.DataMask;
import ch.raph.datamask.log4j2.testdomain.Banking;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Core;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.appender.rewrite.RewriteAppender;
import org.apache.logging.log4j.core.config.AppenderRef;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.DefaultConfiguration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.config.plugins.util.PluginManager;
import org.apache.logging.log4j.core.config.plugins.util.PluginType;
import org.apache.logging.log4j.core.impl.ContextDataFactory;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.core.pattern.PatternConverter;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Masking every event a rewrite appender forwards")
class MaskingRewritePolicyTest {

    private static final String SECRET = "a-test-secret-of-sufficient-length";
    private static final String IBAN = "CH9300762011623852957";
    private static final String EMAIL = "john.doe@example.com";
    private static final String LOGGER = "ch.example.PaymentService";

    @AfterEach
    void uninstall() {
        DataMaskLog4j2.uninstall();
    }

    @Nested
    @DisplayName("Rewriting")
    class Rewriting {

        @Test
        @DisplayName("masks the event, leaving the appenders underneath it configured exactly as they were")
        void masksTheEvent() {
            Capturing capturing = new Capturing();
            RewriteAppender rewrite = rewriteAppender(new MaskingRewritePolicy(dataMask()), capturing);

            rewrite.append(event("crediting {}", IBAN));

            assertThat(capturing.events).hasSize(1);
            assertThat(capturing.events.getFirst().getMessage().getFormattedMessage())
                    .doesNotContain(IBAN)
                    .isEqualTo("crediting CH93 **** **** **** *295 7");
        }

        @Test
        @DisplayName("masks the exception and the thread context map on the way through, not only the message")
        void masksEverythingTheEventCarries() {
            Capturing capturing = new Capturing();
            RewriteAppender rewrite = rewriteAppender(new MaskingRewritePolicy(dataMask()), capturing);

            rewrite.append(new Log4jLogEvent.Builder()
                    .setLoggerName(LOGGER)
                    .setLevel(Level.ERROR)
                    .setMessage(new ParameterizedMessage("insert failed", new Object[0]))
                    .setContextData(ContextDataFactory.createContextData(Map.of("customer", EMAIL)))
                    .setThrown(new IllegalStateException("Key (email)=(" + EMAIL + ") already exists"))
                    .build());

            LogEvent captured = capturing.events.getFirst();
            assertThat(captured.getContextData().<String>getValue("customer")).doesNotContain(EMAIL);
            assertThat(captured.getThrown().getMessage()).doesNotContain(EMAIL);
        }

        @Test
        @DisplayName("forwards an event that carried nothing untouched, which is what keeps a clean line free")
        void forwardsACleanEventUntouched() {
            LogEvent event = event("payment {} accepted", "PMT-1");

            LogEvent rewritten = new MaskingRewritePolicy(dataMask()).rewrite(event);

            assertThat(rewritten).isSameAs(event);
        }
    }

    @Nested
    @DisplayName("Garbage-free logging")
    class GarbageFree {

        @Test
        @DisplayName("masks a declared field through a real logger running garbage-free, the default outside a web app")
        void masksThroughARealGarbageFreeLogger() {
            // Reusable messages and mutable events only flow when threadlocals are on. They are on by
            // default in a plain JVM like this test's; the assertion is here so the test fails loudly
            // instead of silently exercising the immutable path if that ever changes.
            assertThat(org.apache.logging.log4j.util.Constants.ENABLE_THREADLOCALS)
                    .as("log4j2 must run garbage-free for this test to mean anything")
                    .isTrue();

            LoggerContext context = new LoggerContext("garbage-free");
            try {
                context.start();
                Configuration configuration = context.getConfiguration();
                Rendering rendering = new Rendering();
                configuration.addAppender(rendering);
                RewriteAppender rewrite = RewriteAppender.createAppender(
                        "MASKED",
                        "true",
                        new AppenderRef[] {AppenderRef.createAppenderRef(rendering.getName(), null, null)},
                        configuration,
                        new MaskingRewritePolicy(dataMask()),
                        null);
                rewrite.start();
                configuration.addAppender(rewrite);
                LoggerConfig loggerConfig = new LoggerConfig(LOGGER, Level.INFO, false);
                loggerConfig.addAppender(rewrite, Level.INFO, null);
                configuration.addLogger(LOGGER, loggerConfig);
                context.updateLoggers();

                context.getLogger(LOGGER)
                        .info("customer {}", new Banking.Customer(new Banking.Email(EMAIL), IBAN, "CH"));

                assertThat(rendering.lines)
                        .singleElement()
                        .satisfies(line -> assertThat(line)
                                .doesNotContain(IBAN)
                                .doesNotContain(EMAIL)
                                .contains("CH93 **** **** **** *295 7"));
            } finally {
                context.stop();
            }
        }

        /**
         * Renders at append time: a garbage-free event must not be kept and read later, which is the
         * very lifecycle this test is about.
         */
        private static final class Rendering extends AbstractAppender {

            private final List<String> lines = new ArrayList<>();

            private Rendering() {
                super("RENDERED", null, null, true, Property.EMPTY_ARRAY);
                start();
            }

            @Override
            public void append(LogEvent event) {
                lines.add(event.getMessage().getFormattedMessage());
            }
        }
    }

    @Nested
    @DisplayName("Where the DataMask comes from")
    class Wiring {

        @Test
        @DisplayName("builds its own engine from the secret attribute, for a configuration written in XML")
        void usesTheSecretAttribute() {
            LogEvent masked = MaskingRewritePolicy.createPolicy(SECRET).rewrite(event("crediting {}", IBAN));

            assertThat(masked.getMessage().getFormattedMessage()).doesNotContain(IBAN);
        }

        @Test
        @DisplayName("uses the installed instance when the configuration named no secret")
        void usesTheInstalledInstance() {
            DataMaskLog4j2.install(dataMask());

            LogEvent masked = MaskingRewritePolicy.createPolicy(null).rewrite(event("crediting {}", IBAN));

            assertThat(masked.getMessage().getFormattedMessage()).doesNotContain(IBAN);
        }

        @Test
        @DisplayName("picks up an instance installed after logging began, since beans outlive log4j2's own startup")
        void picksUpALateInstall() {
            MaskingRewritePolicy policy = MaskingRewritePolicy.createPolicy(null);
            policy.rewrite(event("crediting {}", IBAN));

            DataMaskLog4j2.install(dataMask());
            LogEvent masked = policy.rewrite(event("crediting {}", IBAN));

            assertThat(masked.getMessage().getFormattedMessage()).doesNotContain(IBAN);
        }

        @Test
        @DisplayName("masks with strict defaults when nothing is configured at all, rather than passing the value on")
        void fallsBackToStrictDefaults() {
            LogEvent masked = MaskingRewritePolicy.createPolicy(null).rewrite(event("crediting {}", IBAN));

            assertThat(masked.getMessage().getFormattedMessage()).doesNotContain(IBAN);
        }

        @Test
        @DisplayName("keeps masking under a rejected secret rather than dropping every log line")
        void survivesARejectedSecret() {
            LogEvent masked = MaskingRewritePolicy.createPolicy("too-short").rewrite(event("crediting {}", IBAN));

            assertThat(masked.getMessage().getFormattedMessage()).doesNotContain(IBAN);
        }

        @Test
        @DisplayName("names no secret in the description log4j2 writes to its status log")
        void doesNotPrintTheSecret() {
            assertThat(MaskingRewritePolicy.createPolicy(SECRET).toString()).doesNotContain(SECRET);
        }
    }

    @Nested
    @DisplayName("Plugin registration")
    class Registration {

        @Test
        @DisplayName("is in the descriptor log4j2 already reads, so no configuration has to scan a package for it")
        void policyIsInTheDescriptor() {
            assertThat(pluginType(Core.CATEGORY_NAME, "DataMask")).isNotNull();
        }

        @Test
        @DisplayName("registers the pattern converter the same way")
        void converterIsInTheDescriptor() {
            assertThat(pluginType(PatternConverter.CATEGORY, "MaskingMessageConverter"))
                    .isNotNull();
        }

        private PluginType<?> pluginType(String category, String name) {
            PluginManager manager = new PluginManager(category);
            // No package argument: this reads Log4j2Plugins.dat from the classpath, which is what a
            // real configuration does and what the build-time annotation processor wrote.
            manager.collectPlugins();
            return manager.getPluginType(name);
        }
    }

    private static DataMask dataMask() {
        return DataMask.builder().secret(SECRET).build();
    }

    private static LogEvent event(String format, Object... parameters) {
        return new Log4jLogEvent.Builder()
                .setLoggerName(LOGGER)
                .setLevel(Level.INFO)
                .setMessage(new ParameterizedMessage(format, parameters))
                .build();
    }

    private static RewriteAppender rewriteAppender(MaskingRewritePolicy policy, Appender target) {
        Configuration configuration = new DefaultConfiguration();
        configuration.addAppender(target);
        AppenderRef[] refs = {AppenderRef.createAppenderRef(target.getName(), null, null)};
        RewriteAppender rewrite = RewriteAppender.createAppender("MASKED", "true", refs, configuration, policy, null);
        rewrite.start();
        return rewrite;
    }

    /** Kept here rather than as a plugin: an appender constructed in code needs no registration. */
    private static final class Capturing extends AbstractAppender {

        private final List<LogEvent> events = new ArrayList<>();

        private Capturing() {
            super("CAPTURED", null, null, true, Property.EMPTY_ARRAY);
            start();
        }

        @Override
        public void append(LogEvent event) {
            events.add(event);
        }
    }
}
