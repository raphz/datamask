package ch.raph.datamask.log4j2;

import static org.assertj.core.api.Assertions.assertThat;

import ch.raph.datamask.api.MaskStrategy;
import ch.raph.datamask.api.PiiCategory;
import ch.raph.datamask.application.DataMask;
import ch.raph.datamask.domain.FailureMode;
import ch.raph.datamask.domain.MaskingObserver;
import ch.raph.datamask.domain.MaskingPolicy;
import ch.raph.datamask.log4j2.testdomain.Banking;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.config.DefaultConfiguration;
import org.apache.logging.log4j.core.impl.ContextDataFactory;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.message.ObjectMessage;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.logging.log4j.message.SimpleMessage;
import org.apache.logging.log4j.message.StringMapMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Masking a log event")
class LogEventMaskerTest {

    private static final String SECRET = "a-test-secret-of-sufficient-length";
    private static final String IBAN = "CH9300762011623852957";
    private static final String MASKED_IBAN = "CH93 **** **** **** *295 7";
    private static final String EMAIL = "john.doe@example.com";
    private static final String MASKED_EMAIL = "j*******@e******.com";
    private static final String CARD = "4111111111111111";
    private static final String LOGGER = "ch.example.PaymentService";

    private final LogEventMasker masker =
            new LogEventMasker(DataMask.builder().secret(SECRET).build());

    @Nested
    @DisplayName("Parameters")
    class Parameters {

        @Test
        @DisplayName("masks a declared field of a parameter, so the rendered line never held the value")
        void masksDeclaredFieldOfAParameter() {
            LogEvent masked = masker.mask(event("customer {}", customer()));

            assertThat(masked.getMessage().getFormattedMessage())
                    .doesNotContain(IBAN)
                    .doesNotContain(EMAIL)
                    .contains(MASKED_IBAN)
                    .contains(MASKED_EMAIL);
        }

        @Test
        @DisplayName("masks the parameter array itself, for a layout that writes the parameters")
        void masksTheParameterArray() {
            LogEvent masked = masker.mask(event("customer {}", customer()));

            Banking.Customer parameter = (Banking.Customer) masked.getMessage().getParameters()[0];
            assertThat(parameter.iban()).doesNotContain(IBAN);
            assertThat(parameter.email().value()).doesNotContain(EMAIL);
        }

        @Test
        @DisplayName("masks a bare string parameter a detector recognises, which no annotation covered")
        void masksBareStringParameter() {
            LogEvent masked = masker.mask(event("crediting {}", IBAN));

            assertThat(masked.getMessage().getFormattedMessage())
                    .doesNotContain(IBAN)
                    .isEqualTo("crediting " + MASKED_IBAN);
        }

        @Test
        @DisplayName("keeps the last four digits of a card number, as PCI-DSS 3.3 requires")
        void masksCardNumber() {
            LogEvent masked = masker.mask(event("charging {}", new Banking.Card(CARD, "123")));

            assertThat(masked.getMessage().getFormattedMessage())
                    .doesNotContain(CARD)
                    .contains("**** **** **** 1111")
                    .contains("cvv=****");
        }

        @Test
        @DisplayName("leaves the object that was logged untouched, because the caller is still using it")
        void doesNotMutateTheParameter() {
            Banking.Customer customer = customer();

            masker.mask(event("customer {}", customer));

            assertThat(customer.iban()).isEqualTo(IBAN);
        }

        @Test
        @DisplayName("masks the message of a throwable passed as a parameter, keeping its type readable")
        void masksThrowableParameter() {
            IllegalStateException failure = new IllegalStateException("Key (email)=(" + EMAIL + ") already exists");

            LogEvent masked = masker.mask(event("rejected {} for {}", failure, "PMT-1"));

            assertThat(masked.getMessage().getFormattedMessage())
                    .doesNotContain(EMAIL)
                    .contains("IllegalStateException")
                    .contains(MASKED_EMAIL);
        }
    }

    @Nested
    @DisplayName("The message body")
    class MessageBody {

        @Test
        @DisplayName("masks an IBAN concatenated into the message, which has no parameter to declare")
        void masksConcatenatedMessage() {
            LogEvent masked = masker.mask(simple("payment from " + IBAN + " accepted"));

            assertThat(masked.getMessage().getFormattedMessage())
                    .doesNotContain(IBAN)
                    .isEqualTo("payment from " + MASKED_IBAN + " accepted");
        }

        @Test
        @DisplayName("masks a value in the message format even when every parameter was clean")
        void masksFormatAroundCleanParameters() {
            LogEvent masked = masker.mask(event("contact " + EMAIL + " about {}", "PMT-1"));

            assertThat(masked.getMessage().getFormattedMessage())
                    .doesNotContain(EMAIL)
                    .isEqualTo("contact " + MASKED_EMAIL + " about PMT-1");
        }

        @Test
        @DisplayName("masks the format the message keeps, not only the text it renders")
        void masksTheFormatItself() {
            LogEvent masked = masker.mask(event("contact " + EMAIL + " about {}", "PMT-1"));

            assertThat(((ParameterizedMessage) masked.getMessage()).getFormat()).doesNotContain(EMAIL);
        }
    }

    @Nested
    @DisplayName("Structured messages")
    class Structured {

        @Test
        @DisplayName("masks a map message value and keeps it a map, so a JSON layout writes the same shape")
        void masksMapMessage() {
            LogEvent event = message(new StringMapMessage(Map.of("iban", IBAN, "reference", "PMT-1")));

            LogEvent masked = masker.mask(event);

            assertThat(masked.getMessage()).isInstanceOf(StringMapMessage.class);
            StringMapMessage map = (StringMapMessage) masked.getMessage();
            assertThat(map.get("iban")).isEqualTo(MASKED_IBAN);
            assertThat(map.get("reference")).isEqualTo("PMT-1");
        }

        @Test
        @DisplayName("masks the object of an object message and keeps it an object message")
        void masksObjectMessage() {
            LogEvent masked = masker.mask(message(new ObjectMessage(customer())));

            assertThat(masked.getMessage()).isInstanceOf(ObjectMessage.class);
            assertThat(masked.getMessage().getFormattedMessage())
                    .doesNotContain(IBAN)
                    .doesNotContain(EMAIL);
        }
    }

    @Nested
    @DisplayName("The thread context map")
    class ContextData {

        @Test
        @DisplayName("masks a context value, the leak that is attached to every line of a request")
        void masksContextValue() {
            LogEvent masked = masker.mask(withContext(Map.of("customer", EMAIL)));

            assertThat(masked.getContextData().<String>getValue("customer")).isEqualTo(MASKED_EMAIL);
        }

        @Test
        @DisplayName("leaves context keys alone, since a pattern or a filter refers to them by name")
        void keepsContextKeys() {
            LogEvent masked = masker.mask(withContext(Map.of("customer", EMAIL, "requestId", "req-42")));

            assertThat(masked.getContextData().toMap()).containsKeys("customer", "requestId");
            assertThat(masked.getContextData().<String>getValue("requestId")).isEqualTo("req-42");
        }
    }

    @Nested
    @DisplayName("Exceptions")
    class Exceptions {

        @Test
        @DisplayName("masks the row a constraint violation quoted back, the leak nobody looks for")
        void masksExceptionMessage() {
            LogEvent masked =
                    masker.mask(thrown(new IllegalStateException("Key (email)=(" + EMAIL + ") already exists")));

            assertThat(masked.getThrown().getMessage()).doesNotContain(EMAIL).contains(MASKED_EMAIL);
        }

        @Test
        @DisplayName("keeps the exception type, which is the one thing a reader trusts a trace for")
        void keepsTheExceptionType() {
            LogEvent masked = masker.mask(thrown(new IllegalStateException("Key (email)=(" + EMAIL + ")")));

            assertThat(masked.getThrown()).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("keeps the frames, which identify code rather than a person")
        void keepsTheFrames() {
            IllegalStateException failure = new IllegalStateException("Key (email)=(" + EMAIL + ")");

            LogEvent masked = masker.mask(thrown(failure));

            assertThat(masked.getThrown().getStackTrace()).isEqualTo(failure.getStackTrace());
        }

        @Test
        @DisplayName("masks a cause further down the chain, which is where a driver's message ends up")
        void masksCauseMessage() {
            Throwable cause = new IllegalStateException("Key (iban)=(" + IBAN + ") already exists");

            LogEvent masked = masker.mask(thrown(new RuntimeException("could not save", cause)));

            assertThat(masked.getThrown().getCause().getMessage())
                    .doesNotContain(IBAN)
                    .contains(MASKED_IBAN);
        }

        @Test
        @DisplayName("masks a suppressed exception, which is printed with the trace like any other")
        void masksSuppressedMessage() {
            RuntimeException failure = new RuntimeException("commit failed");
            failure.addSuppressed(new IllegalStateException("rollback of " + IBAN + " failed"));

            LogEvent masked = masker.mask(thrown(failure));

            assertThat(masked.getThrown().getSuppressed()[0].getMessage())
                    .doesNotContain(IBAN)
                    .contains(MASKED_IBAN);
        }

        @Test
        @DisplayName("names the original type in a stand-in when the exception cannot be rebuilt")
        void standsInForAnUnreconstructableType() {
            LogEvent masked = masker.mask(thrown(new Banking.UnreconstructableException(null, 0)));

            assertThat(masked.getThrown().toString())
                    .doesNotContain(IBAN)
                    .startsWith(Banking.UnreconstructableException.class.getName())
                    .contains(MASKED_IBAN);
        }

        @Test
        @DisplayName("forwards an exception that carried nothing as the instance the caller threw")
        void keepsACleanException() {
            IllegalStateException failure = new IllegalStateException("insufficient funds");

            LogEvent masked = masker.mask(thrown(failure));

            assertThat(masked.getThrown()).isSameAs(failure);
        }
    }

    @Nested
    @DisplayName("What actually reaches a layout")
    class Rendering {

        @Test
        @DisplayName("renders no part of the raw values through a pattern covering message, context and exception")
        void rendersNothingRaw() {
            LogEvent event = new Log4jLogEvent.Builder()
                    .setLoggerName(LOGGER)
                    .setLevel(Level.INFO)
                    .setMessage(new ParameterizedMessage("customer {} from {}", new Object[] {customer(), IBAN}))
                    .setContextData(ContextDataFactory.createContextData(Map.of("customer", EMAIL)))
                    .setThrown(new IllegalStateException("Key (email)=(" + EMAIL + ") already exists"))
                    .build();

            String rendered = render("%m %X %xEx", masker.mask(event));

            assertThat(rendered).doesNotContain(IBAN).doesNotContain(EMAIL);
        }
    }

    @Nested
    @DisplayName("A line that carried nothing")
    class Clean {

        @Test
        @DisplayName("returns the same event, which is what keeps a PII-free line allocation-free")
        void returnsTheSameEvent() {
            LogEvent event = new Log4jLogEvent.Builder()
                    .setLoggerName(LOGGER)
                    .setLevel(Level.INFO)
                    .setMessage(new ParameterizedMessage("payment {} accepted in {} ms", new Object[] {"PMT-1", 12}))
                    .setContextData(ContextDataFactory.createContextData(Map.of("requestId", "req-42")))
                    .build();

            assertThat(masker.mask(event)).isSameAs(event);
        }

        @Test
        @DisplayName("returns the same event when it has no parameters, no context and no exception at all")
        void returnsTheSameBareEvent() {
            LogEvent event = simple("started");

            assertThat(masker.mask(event)).isSameAs(event);
        }
    }

    @Nested
    @DisplayName("Observation")
    class Observation {

        @Test
        @DisplayName("reports a detector hit in the message, the earliest warning that a log line leaks")
        void reportsUndeclaredPiiInTheMessage() {
            Recorder recorder = new Recorder();

            observedBy(recorder).mask(simple("payment from " + IBAN));

            assertThat(recorder.undeclared).containsExactly(LOGGER + ".message:IBAN");
        }

        @Test
        @DisplayName("reports a detector hit in the context map with the key, so the code that set it can be found")
        void reportsUndeclaredPiiInContextData() {
            Recorder recorder = new Recorder();

            observedBy(recorder).mask(withContext(Map.of("customer", EMAIL)));

            assertThat(recorder.undeclared).containsExactly(LOGGER + ".context.customer:EMAIL");
        }

        @Test
        @DisplayName("names the parameter position of a detector hit, since the format string will not say which")
        void reportsUndeclaredPiiInAParameter() {
            Recorder recorder = new Recorder();

            observedBy(recorder).mask(event("crediting {} and {}", "PMT-1", IBAN));

            assertThat(recorder.undeclared).containsExactly(LOGGER + ".arg1:IBAN");
        }

        @Test
        @DisplayName("reports a declared parameter field with its path and category, for the compliance record")
        void reportsDeclaredMasking() {
            Recorder recorder = new Recorder();

            observedBy(recorder).mask(event("customer {}", customer()));

            assertThat(recorder.masked).contains("Customer.iban:IBAN:IBAN");
        }
    }

    @Nested
    @DisplayName("Failing closed")
    class FailingClosed {

        @Test
        @DisplayName("renders the placeholder when a masker throws, never the value it failed to mask")
        void redactsWhenTheMaskerThrows() {
            LogEvent masked = masker.mask(event("checking {}", new Banking.Fragile(IBAN)));

            assertThat(masked.getMessage().getFormattedMessage())
                    .doesNotContain(IBAN)
                    .contains("****");
        }

        @Test
        @DisplayName(
                "withholds the message rather than throwing under FailureMode.THROW, since a log call must not fail")
        void withholdsUnderThrow() {
            LogEvent event = new Log4jLogEvent.Builder()
                    .setLoggerName(LOGGER)
                    .setLevel(Level.INFO)
                    .setMessage(new ParameterizedMessage("checking {}", new Object[] {new Banking.Fragile(IBAN)}))
                    .setContextData(ContextDataFactory.createContextData(Map.of("customer", EMAIL)))
                    .setThrown(new IllegalStateException("Key (email)=(" + EMAIL + ")"))
                    .build();

            LogEvent masked = throwingMasker().mask(event);

            assertThat(masked.getMessage().getFormattedMessage())
                    .doesNotContain(IBAN)
                    .contains("withheld");
            assertThat(masked.getContextData().isEmpty()).isTrue();
            assertThat(masked.getThrown()).isNull();
            assertThat(render("%m %X %xEx", masked)).doesNotContain(EMAIL).doesNotContain(IBAN);
        }

        @Test
        @DisplayName("keeps the level, the logger and the instant of a withheld event, so the line is still there")
        void withheldEventKeepsItsFrame() {
            LogEvent event = event("checking {}", new Banking.Fragile(IBAN));

            LogEvent masked = throwingMasker().mask(event);

            assertThat(masked.getLevel()).isEqualTo(Level.INFO);
            assertThat(masked.getLoggerName()).isEqualTo(LOGGER);
            assertThat(masked.getTimeMillis()).isEqualTo(event.getTimeMillis());
        }

        @Test
        @DisplayName("reports the failure to the observer, without the value in the path it reports")
        void reportsTheFailure() {
            Recorder recorder = new Recorder();
            DataMask throwing = DataMask.builder()
                    .secret(SECRET)
                    .observer(recorder)
                    .policy(MaskingPolicy.strict().withFailureMode(FailureMode.THROW))
                    .build();

            new LogEventMasker(throwing).mask(event("checking {}", new Banking.Fragile(IBAN)));

            assertThat(recorder.failures)
                    .isNotEmpty()
                    .allSatisfy(path -> assertThat(path).doesNotContain(IBAN));
        }

        private LogEventMasker throwingMasker() {
            return new LogEventMasker(DataMask.builder()
                    .secret(SECRET)
                    .policy(MaskingPolicy.strict().withFailureMode(FailureMode.THROW))
                    .build());
        }
    }

    @Nested
    @DisplayName("Policy")
    class Policies {

        @Test
        @DisplayName("leaves free text alone when content scanning is off, so a sandbox log stays readable")
        void respectsScanningBeingOff() {
            LogEvent masked = quiet().mask(simple("payment from " + IBAN));

            assertThat(masked.getMessage().getFormattedMessage()).contains(IBAN);
        }

        @Test
        @DisplayName("still masks a declared field with scanning off, because that was declared, not detected")
        void stillMasksDeclaredFields() {
            LogEvent masked = quiet().mask(event("customer {}", customer()));

            assertThat(masked.getMessage().getFormattedMessage()).doesNotContain(IBAN);
        }

        @Test
        @DisplayName(
                "leaves the context map and exception messages alone with scanning off, the two it can only detect")
        void leavesContextAndExceptionsAloneWithScanningOff() {
            LogEvent event = new Log4jLogEvent.Builder()
                    .setLoggerName(LOGGER)
                    .setLevel(Level.INFO)
                    .setMessage(new SimpleMessage("insert failed"))
                    .setContextData(ContextDataFactory.createContextData(Map.of("customer", EMAIL)))
                    .setThrown(new IllegalStateException("Key (iban)=(" + IBAN + ")"))
                    .build();

            LogEvent masked = quiet().mask(event);

            assertThat(masked.getContextData().<String>getValue("customer")).isEqualTo(EMAIL);
            assertThat(masked.getThrown().getMessage()).contains(IBAN);
        }

        @Test
        @DisplayName("produces the same pseudonym for the same value, so a customer stays traceable across lines")
        void hashesConsistently() {
            String first = masker.mask(event("ref {}", new Banking.Reference("customer-7")))
                    .getMessage()
                    .getFormattedMessage();
            String second = masker.mask(event("ref {}", new Banking.Reference("customer-7")))
                    .getMessage()
                    .getFormattedMessage();

            assertThat(first).doesNotContain("customer-7").isEqualTo(second);
        }

        private LogEventMasker quiet() {
            return new LogEventMasker(DataMask.builder()
                    .secret(SECRET)
                    .policy(MaskingPolicy.strict().withScanUnannotatedText(false))
                    .build());
        }
    }

    private LogEventMasker observedBy(MaskingObserver observer) {
        return new LogEventMasker(
                DataMask.builder().secret(SECRET).observer(observer).build());
    }

    private static String render(String pattern, LogEvent event) {
        return PatternLayout.newBuilder()
                .withConfiguration(new DefaultConfiguration())
                .withPattern(pattern)
                .build()
                .toSerializable(event);
    }

    private static Banking.Customer customer() {
        return new Banking.Customer(new Banking.Email(EMAIL), IBAN, "CH");
    }

    private static LogEvent event(String format, Object... parameters) {
        return message(new ParameterizedMessage(format, parameters));
    }

    private static LogEvent simple(String text) {
        return message(new SimpleMessage(text));
    }

    private static LogEvent message(org.apache.logging.log4j.message.Message message) {
        return new Log4jLogEvent.Builder()
                .setLoggerName(LOGGER)
                .setLevel(Level.INFO)
                .setMessage(message)
                .build();
    }

    private static LogEvent thrown(Throwable failure) {
        return new Log4jLogEvent.Builder()
                .setLoggerName(LOGGER)
                .setLevel(Level.ERROR)
                .setMessage(new SimpleMessage("insert failed"))
                .setThrown(failure)
                .build();
    }

    private static LogEvent withContext(Map<String, String> contextData) {
        return new Log4jLogEvent.Builder()
                .setLoggerName(LOGGER)
                .setLevel(Level.INFO)
                .setMessage(new SimpleMessage("processing"))
                .setContextData(ContextDataFactory.createContextData(contextData))
                .build();
    }

    private static final class Recorder implements MaskingObserver {

        private final List<String> masked = new ArrayList<>();
        private final List<String> undeclared = new ArrayList<>();
        private final List<String> failures = new ArrayList<>();

        @Override
        public void onMasked(String path, PiiCategory category, MaskStrategy strategy) {
            masked.add(path + ":" + category + ":" + strategy);
        }

        @Override
        public void onUnannotatedPii(String path, PiiCategory category, String detector) {
            undeclared.add(path + ":" + category);
        }

        @Override
        public void onFailure(String path, Throwable error) {
            failures.add(path);
        }
    }
}
