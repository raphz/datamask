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
import org.apache.logging.log4j.core.impl.MutableLogEvent;
import org.apache.logging.log4j.core.impl.ThrowableProxy;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.message.ObjectMessage;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.logging.log4j.message.ReusableMessage;
import org.apache.logging.log4j.message.ReusableObjectMessage;
import org.apache.logging.log4j.message.ReusableParameterizedMessage;
import org.apache.logging.log4j.message.ReusableSimpleMessage;
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

    /** The scheme and site every path this module reports hangs off: {@code <module>:<site>[/<detail>]}. */
    private static final String ORIGIN = "log4j2:" + LOGGER;

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
    @DisplayName("Garbage-free reusable messages")
    class GarbageFree {

        @Test
        @DisplayName("masks a declared field of a reusable message parameter, the shape a garbage-free logger produces")
        void masksDeclaredFieldOfAReusableParameter() {
            LogEvent masked = masker.mask(message(reusable("customer {}", customer())));

            assertThat(masked.getMessage().getFormattedMessage())
                    .doesNotContain(IBAN)
                    .doesNotContain(EMAIL)
                    .contains(MASKED_IBAN)
                    .contains(MASKED_EMAIL);
        }

        @Test
        @DisplayName("still masks a declared field with scanning off, exactly as the immutable message path does")
        void stillMasksDeclaredFieldsWithScanningOff() {
            LogEventMasker quiet = new LogEventMasker(DataMask.builder()
                    .secret(SECRET)
                    .policy(MaskingPolicy.strict().withScanUnannotatedText(false))
                    .build());

            LogEvent masked = quiet.mask(message(reusable("customer {}", customer())));

            assertThat(masked.getMessage().getFormattedMessage())
                    .doesNotContain(IBAN)
                    .doesNotContain(EMAIL);
        }

        @Test
        @DisplayName("masks a bare string parameter of a reusable message, which no annotation covered")
        void masksBareStringReusableParameter() {
            LogEvent masked = masker.mask(message(reusable("crediting {}", IBAN)));

            assertThat(masked.getMessage().getFormattedMessage())
                    .doesNotContain(IBAN)
                    .isEqualTo("crediting " + MASKED_IBAN);
        }

        @Test
        @DisplayName("masks a value in the format of a reusable message, even when every parameter was clean")
        void masksTheFormatOfAReusableMessage() {
            LogEvent masked = masker.mask(message(reusable("contact " + EMAIL + " about {}", "PMT-1")));

            assertThat(masked.getMessage().getFormattedMessage())
                    .doesNotContain(EMAIL)
                    .isEqualTo("contact " + MASKED_EMAIL + " about PMT-1");
        }

        @Test
        @DisplayName("materializes the masked message outside the reusable lifecycle, surviving the recycling")
        void materializesOutsideTheReusableLifecycle() {
            ReusableParameterizedMessage recycled = reusable("customer {}", customer());

            LogEvent masked = masker.mask(message(recycled));
            recycled.clear(); // what the logger does to the instance right after the call returns

            assertThat(masked.getMessage()).isNotInstanceOf(ReusableMessage.class);
            assertThat(masked.getMessage().getFormattedMessage())
                    .doesNotContain(IBAN)
                    .contains(MASKED_IBAN);
        }

        @Test
        @DisplayName("keeps a clean reusable event as the same instance, inside the allocation-free lifecycle")
        void keepsACleanReusableEvent() {
            LogEvent event = message(reusable("payment {} accepted in {} ms", "PMT-1", 12));

            assertThat(masker.mask(event)).isSameAs(event);
        }

        @Test
        @DisplayName("masks a mutable log event standing in for its own message, which async appenders hand over")
        void masksAMutableLogEventActingAsItsOwnMessage() {
            MutableLogEvent mutable = new MutableLogEvent();
            mutable.setLoggerName(LOGGER);
            mutable.setLevel(Level.INFO);
            mutable.setMessage(reusable("customer {}", customer()));

            LogEvent masked = masker.mask(mutable);
            mutable.clear();

            assertThat(masked).isNotSameAs(mutable);
            assertThat(masked.getMessage().getFormattedMessage())
                    .doesNotContain(IBAN)
                    .doesNotContain(EMAIL)
                    .contains(MASKED_IBAN);
        }

        @Test
        @DisplayName("masks a reusable object message and materializes it as an object message")
        void masksAReusableObjectMessage() {
            ReusableObjectMessage object = new ReusableObjectMessage();
            object.set(customer());

            LogEvent masked = masker.mask(message(object));

            assertThat(masked.getMessage()).isInstanceOf(ObjectMessage.class);
            assertThat(masked.getMessage().getFormattedMessage())
                    .doesNotContain(IBAN)
                    .doesNotContain(EMAIL);
        }

        @Test
        @DisplayName("masks the text of a reusable simple message, which has no parameter to declare")
        void masksAReusableSimpleMessage() {
            ReusableSimpleMessage simple = new ReusableSimpleMessage();
            simple.set("payment from " + IBAN + " accepted");

            LogEvent masked = masker.mask(message(simple));

            assertThat(masked.getMessage().getFormattedMessage())
                    .doesNotContain(IBAN)
                    .isEqualTo("payment from " + MASKED_IBAN + " accepted");
        }

        @Test
        @DisplayName("names the parameter position of a detector hit in a reusable message")
        void reportsTheParameterPosition() {
            Recorder recorder = new Recorder();

            observedBy(recorder).mask(message(reusable("crediting {} and {}", "PMT-1", IBAN)));

            assertThat(recorder.undeclared).containsExactly(ORIGIN + "/arg1:IBAN");
        }

        @Test
        @DisplayName("withholds a reusable message it could not mask, never passing the raw text through")
        void withholdsAReusableMessageItCouldNotMask() {
            LogEventMasker throwing = new LogEventMasker(DataMask.builder()
                    .secret(SECRET)
                    .policy(MaskingPolicy.strict().withFailureMode(FailureMode.THROW))
                    .build());

            LogEvent masked = throwing.mask(message(reusable("checking {}", new Banking.Fragile(IBAN))));

            assertThat(masked.getMessage().getFormattedMessage())
                    .doesNotContain(IBAN)
                    .contains("withheld");
        }

        private ReusableParameterizedMessage reusable(String format, Object... parameters) {
            return new ReusableParameterizedMessage().set(format, parameters);
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
        @DisplayName("names the original type in the stand-in's message, which is what a JSON layout writes")
        void standInMessageNamesTheOriginalType() {
            LogEvent masked = masker.mask(thrown(new Banking.UnreconstructableException(null, 0)));

            // exception.class in a JSON layout is read off getClass() and can only be this stand-in's;
            // the message is the one field the original type can still be carried in.
            assertThat(masked.getThrown().getMessage())
                    .doesNotContain(IBAN)
                    .startsWith(Banking.UnreconstructableException.class.getName() + ": ")
                    .contains(MASKED_IBAN);
        }

        @Test
        @DisplayName("names the original type through the throwable proxy every layout is derived from")
        @SuppressWarnings("deprecation") // ThrowableProxy is on its way out of log4j2, but it is what
        // the layouts in the field still render an exception through, so it is what this has to hold for.
        void standInNamesTheOriginalTypeThroughTheProxy() {
            LogEvent masked = masker.mask(thrown(new Banking.UnreconstructableException(null, 0)));

            ThrowableProxy proxy = new ThrowableProxy(masked.getThrown());

            assertThat(proxy.getMessage())
                    .doesNotContain(IBAN)
                    .contains(Banking.UnreconstructableException.class.getName());
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

            assertThat(recorder.undeclared).containsExactly(ORIGIN + "/message:IBAN");
        }

        @Test
        @DisplayName("reports a detector hit in the context map with the key, so the code that set it can be found")
        void reportsUndeclaredPiiInContextData() {
            Recorder recorder = new Recorder();

            observedBy(recorder).mask(withContext(Map.of("customer", EMAIL)));

            assertThat(recorder.undeclared).containsExactly(ORIGIN + "/mdc/customer:EMAIL");
        }

        @Test
        @DisplayName("names the parameter position of a detector hit, since the format string will not say which")
        void reportsUndeclaredPiiInAParameter() {
            Recorder recorder = new Recorder();

            observedBy(recorder).mask(event("crediting {} and {}", "PMT-1", IBAN));

            assertThat(recorder.undeclared).containsExactly(ORIGIN + "/arg1:IBAN");
        }

        @Test
        @DisplayName("reports a declared parameter field with its path and category, for the compliance record")
        void reportsDeclaredMasking() {
            Recorder recorder = new Recorder();

            observedBy(recorder).mask(event("customer {}", customer()));

            assertThat(recorder.masked).contains(ORIGIN + "/arg0.iban:IBAN:IBAN");
        }

        @Test
        @DisplayName("names the entry of a map message a detector hit was found in")
        void reportsUndeclaredPiiInAMapMessage() {
            Recorder recorder = new Recorder();

            observedBy(recorder).mask(message(new StringMapMessage(Map.of("account", IBAN))));

            assertThat(recorder.undeclared).containsExactly(ORIGIN + "/message/account:IBAN");
        }

        @Test
        @DisplayName("names the thrown exception, its cause and its suppressed entries separately")
        void reportsUndeclaredPiiDownTheThrowableGraph() {
            Recorder recorder = new Recorder();
            RuntimeException failure = new RuntimeException(
                    "commit of " + IBAN + " failed",
                    new IllegalStateException("Key (email)=(" + EMAIL + ") already exists"));
            failure.addSuppressed(new IllegalStateException("rollback of " + IBAN + " failed"));

            observedBy(recorder).mask(thrown(failure));

            assertThat(recorder.undeclared)
                    .containsExactlyInAnyOrder(
                            ORIGIN + "/throwable:IBAN",
                            ORIGIN + "/throwable/cause:EMAIL",
                            ORIGIN + "/throwable/suppressed/0:IBAN");
        }

        @Test
        @DisplayName("reports a failure against a scheme-prefixed path too, so a SIEM rule keys on one grammar")
        void reportsFailuresUnderTheSameScheme() {
            Recorder recorder = new Recorder();
            DataMask throwing = DataMask.builder()
                    .secret(SECRET)
                    .observer(recorder)
                    .policy(MaskingPolicy.strict().withFailureMode(FailureMode.THROW))
                    .build();

            new LogEventMasker(throwing).mask(event("checking {}", new Banking.Fragile(IBAN)));

            assertThat(recorder.failures)
                    .isNotEmpty()
                    .allSatisfy(path -> assertThat(path).startsWith(ORIGIN + "/"));
        }

        @Test
        @DisplayName("prefixes every path it reports with the module scheme, whichever site the value came from")
        void everyPathCarriesTheScheme() {
            Recorder recorder = new Recorder();
            LogEvent event = new Log4jLogEvent.Builder()
                    .setLoggerName(LOGGER)
                    .setLevel(Level.ERROR)
                    .setMessage(new ParameterizedMessage("customer {} from " + EMAIL, new Object[] {customer(), IBAN}))
                    .setContextData(ContextDataFactory.createContextData(Map.of("customer", EMAIL)))
                    .setThrown(new IllegalStateException("Key (iban)=(" + IBAN + ")"))
                    .build();

            observedBy(recorder).mask(event);

            assertThat(recorder.everything())
                    .isNotEmpty()
                    .allSatisfy(path -> assertThat(path).startsWith("log4j2:"));
        }

        @Test
        @DisplayName("names the root logger rather than leaving the site of the path empty")
        void namesTheRootLogger() {
            Recorder recorder = new Recorder();
            LogEvent event = new Log4jLogEvent.Builder()
                    .setLoggerName("")
                    .setLevel(Level.INFO)
                    .setMessage(new SimpleMessage("payment from " + IBAN))
                    .build();

            observedBy(recorder).mask(event);

            assertThat(recorder.undeclared).containsExactly("log4j2:<root>/message:IBAN");
        }
    }

    @Nested
    @DisplayName("Bounded traversal of an exception graph")
    class Bounds {

        @Test
        @DisplayName("cuts a cause chain deeper than the policy allows and reports it as a depth limit")
        void reportsTheDepthLimitOfACauseChain() {
            Recorder recorder = new Recorder();
            Throwable deepest = new IllegalStateException("Key (iban)=(" + IBAN + ")");
            Throwable failure = new RuntimeException("outer", new RuntimeException("inner", deepest));

            boundedBy(MaskingPolicy.strict().withMaxDepth(1), recorder).mask(thrown(failure));

            assertThat(recorder.depthLimits).containsExactly(ORIGIN + "/throwable/cause/cause");
            assertThat(recorder.truncations).isEmpty();
        }

        @Test
        @DisplayName("keeps nothing of a cause below the depth limit, which is the fail-closed direction")
        void dropsTheCauseBelowTheDepthLimit() {
            Throwable deepest = new IllegalStateException("Key (iban)=(" + IBAN + ")");
            Throwable failure = new RuntimeException("outer", new RuntimeException("inner", deepest));

            LogEvent masked = boundedBy(MaskingPolicy.strict().withMaxDepth(1), new Recorder())
                    .mask(thrown(failure));

            assertThat(masked.getThrown().getCause().getCause()).isNull();
            assertThat(render("%xEx", masked)).doesNotContain(IBAN);
        }

        @Test
        @DisplayName("cuts a suppressed list longer than the policy allows and reports the list, not each element")
        void reportsTheSuppressedListAsATruncation() {
            Recorder recorder = new Recorder();
            RuntimeException failure = new RuntimeException("batch failed");
            for (int i = 0; i < 5; i++) {
                failure.addSuppressed(new IllegalStateException("item " + i + " of " + IBAN + " failed"));
            }

            boundedBy(MaskingPolicy.strict().withMaxCollectionElements(2), recorder)
                    .mask(thrown(failure));

            assertThat(recorder.truncations).containsExactly(ORIGIN + "/throwable/suppressed:2");
            assertThat(recorder.depthLimits).isEmpty();
        }

        @Test
        @DisplayName("drops the tail of the suppressed list rather than following it, which discloses nothing")
        void dropsTheTailOfTheSuppressedList() {
            RuntimeException failure = new RuntimeException("batch failed");
            for (int i = 0; i < 5; i++) {
                failure.addSuppressed(new IllegalStateException("item " + i + " of " + IBAN + " failed"));
            }

            LogEvent masked = boundedBy(MaskingPolicy.strict().withMaxCollectionElements(2), new Recorder())
                    .mask(thrown(failure));

            assertThat(masked.getThrown().getSuppressed()).hasSize(2);
            assertThat(render("%xEx", masked)).doesNotContain(IBAN);
        }

        @Test
        @DisplayName("leaves a suppressed list within the bound alone, reporting no truncation at all")
        void keepsASuppressedListWithinTheBound() {
            Recorder recorder = new Recorder();
            RuntimeException failure = new RuntimeException("commit failed");
            failure.addSuppressed(new IllegalStateException("rollback of " + IBAN + " failed"));

            LogEvent masked = boundedBy(MaskingPolicy.strict(), recorder).mask(thrown(failure));

            assertThat(recorder.truncations).isEmpty();
            assertThat(masked.getThrown().getSuppressed()).hasSize(1);
        }

        private LogEventMasker boundedBy(MaskingPolicy policy, MaskingObserver observer) {
            return new LogEventMasker(DataMask.builder()
                    .secret(SECRET)
                    .policy(policy)
                    .observer(observer)
                    .build());
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
        private final List<String> scanned = new ArrayList<>();
        private final List<String> failures = new ArrayList<>();
        private final List<String> depthLimits = new ArrayList<>();
        private final List<String> truncations = new ArrayList<>();

        @Override
        public void onMasked(String path, PiiCategory category, MaskStrategy strategy) {
            masked.add(path + ":" + category + ":" + strategy);
        }

        @Override
        public void onUnannotatedPii(String path, PiiCategory category, String detector) {
            undeclared.add(path + ":" + category);
        }

        @Override
        public void onScanned(String path, PiiCategory category, String detector) {
            scanned.add(path + ":" + category);
        }

        @Override
        public void onFailure(String path, Throwable error) {
            failures.add(path);
        }

        @Override
        public void onDepthLimitExceeded(String path) {
            depthLimits.add(path);
        }

        @Override
        public void onCollectionTruncated(String path, int kept) {
            truncations.add(path + ":" + kept);
        }

        /** Every path reported, whichever signal carried it — for asserting the grammar as a whole. */
        private List<String> everything() {
            List<String> paths = new ArrayList<>();
            paths.addAll(masked);
            paths.addAll(undeclared);
            paths.addAll(scanned);
            paths.addAll(failures);
            paths.addAll(depthLimits);
            paths.addAll(truncations);
            return paths;
        }
    }
}
