package ch.raph.datamask.logback;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.raph.datamask.api.MaskStrategy;
import ch.raph.datamask.api.PiiCategory;
import ch.raph.datamask.application.DataMask;
import ch.raph.datamask.domain.FailureMode;
import ch.raph.datamask.domain.MaskingObserver;
import ch.raph.datamask.domain.MaskingPolicy;
import ch.raph.datamask.logback.testdomain.Banking;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.slf4j.event.KeyValuePair;

@DisplayName("Masking a logging event")
class LoggingEventMaskerTest {

    private static final String SECRET = "a-test-secret-of-sufficient-length";
    private static final String IBAN = "CH9300762011623852957";
    private static final String MASKED_IBAN = "CH93 **** **** **** *295 7";
    private static final String EMAIL = "john.doe@example.com";
    private static final String MASKED_EMAIL = "j*******@e******.com";
    private static final String CARD = "4111111111111111";
    private static final String LOGGER = "ch.example.PaymentService";

    /** The scheme and logger every path this module reports is built from. */
    private static final String ORIGIN = "logback:" + LOGGER;

    private static final LoggerContext CONTEXT = context();

    /**
     * A hand-built context has no MDC adapter — the slf4j provider installs one on the default
     * context only. Sharing the adapter {@link MDC} writes to is what makes {@code MDC.put} in a test
     * visible to the events built below, exactly as it is at runtime.
     */
    private static LoggerContext context() {
        LoggerContext context = new LoggerContext();
        context.setMDCAdapter(MDC.getMDCAdapter());
        return context;
    }

    private final LoggingEventMasker masker =
            new LoggingEventMasker(DataMask.builder().secret(SECRET).build());

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Nested
    @DisplayName("Arguments")
    class Arguments {

        @Test
        @DisplayName("masks a declared field of an argument, so the rendered line never held the value")
        void masksDeclaredFieldOfAnArgument() {
            ILoggingEvent masked = masker.mask(event("customer {}", customer()));

            assertThat(masked.getFormattedMessage())
                    .doesNotContain(IBAN)
                    .doesNotContain(EMAIL)
                    .contains(MASKED_IBAN)
                    .contains(MASKED_EMAIL);
        }

        @Test
        @DisplayName("masks the argument array itself, for an encoder that writes the arguments")
        void masksTheArgumentArray() {
            ILoggingEvent masked = masker.mask(event("customer {}", customer()));

            Banking.Customer argument = (Banking.Customer) masked.getArgumentArray()[0];
            assertThat(argument.iban()).doesNotContain(IBAN);
            assertThat(argument.email().value()).doesNotContain(EMAIL);
        }

        @Test
        @DisplayName("masks a bare string argument a detector recognises, which no annotation covered")
        void masksBareStringArgument() {
            ILoggingEvent masked = masker.mask(event("crediting {}", IBAN));

            assertThat(masked.getFormattedMessage()).doesNotContain(IBAN).isEqualTo("crediting " + MASKED_IBAN);
        }

        @Test
        @DisplayName("keeps the last four digits of a card number, as PCI-DSS 3.3 requires")
        void masksCardNumber() {
            ILoggingEvent masked = masker.mask(event("charging {}", new Banking.Card(CARD, "123")));

            assertThat(masked.getFormattedMessage())
                    .doesNotContain(CARD)
                    .contains("**** **** **** 1111")
                    .contains("cvv=****");
        }

        @Test
        @DisplayName("leaves the object that was logged untouched, because the caller is still using it")
        void doesNotMutateTheArgument() {
            Banking.Customer customer = customer();

            masker.mask(event("customer {}", customer));

            assertThat(customer.iban()).isEqualTo(IBAN);
        }

        @Test
        @DisplayName("masks the message of a throwable passed as an argument, keeping its type readable")
        void masksThrowableArgument() {
            // Not in the last position: logback turns a trailing throwable into the event's own
            // throwable proxy, which is a different path.
            IllegalStateException failure = new IllegalStateException("Key (email)=(" + EMAIL + ") already exists");

            ILoggingEvent masked = masker.mask(event("rejected {} for {}", failure, "PMT-1"));

            assertThat(masked.getFormattedMessage())
                    .doesNotContain(EMAIL)
                    .contains("IllegalStateException")
                    .contains(MASKED_EMAIL);
        }

        @Test
        @DisplayName("forwards a throwable argument that carried nothing as the throwable itself")
        void keepsACleanThrowableArgument() {
            IllegalStateException failure = new IllegalStateException("insufficient funds");

            ILoggingEvent masked = masker.mask(event("rejected {} for {}", failure, "PMT-1"));

            assertThat(masked.getArgumentArray()[0]).isSameAs(failure);
        }
    }

    @Nested
    @DisplayName("Formatting the masked line")
    class Formatting {

        @Test
        @DisplayName("renders a primitive array as its elements, the way logback's own formatting does")
        void rendersPrimitiveArraysLikeLogback() {
            ILoggingEvent masked = masker.mask(event("ids {} for {}", new int[] {1, 2, 3}, IBAN));

            assertThat(masked.getFormattedMessage())
                    .doesNotContain(IBAN)
                    .isEqualTo(logbackWouldRender("ids {} for {}", new int[] {1, 2, 3}, IBAN));
        }

        @Test
        @DisplayName("renders nested arrays element by element, primitive and object alike")
        void rendersNestedArraysLikeLogback() {
            Object nested = new Object[] {new int[] {1, 2}, new String[] {"a", "b"}, new char[] {'x'}};

            ILoggingEvent masked = masker.mask(event("batch {} for {}", nested, IBAN));

            assertThat(masked.getFormattedMessage())
                    .doesNotContain(IBAN)
                    .isEqualTo(logbackWouldRender("batch {} for {}", nested, IBAN));
        }

        @Test
        @DisplayName("drops a trailing throwable argument from the rendering, as logback's own formatting does")
        void dropsATrailingThrowableArgumentLikeLogback() {
            // A trailing throwable that never became the event's proxy: logback fills the placeholders
            // from the arguments before it and leaves the last one unfilled. Masking rewrites a
            // throwable whose message carried a value into text, so which argument is a throwable has
            // to be read off the original array or the placeholders shift.
            ILoggingEvent masked = masker.mask(bare("rejected {} because {}", IBAN, new IllegalStateException("boom")));

            assertThat(masked.getFormattedMessage())
                    .doesNotContain(IBAN)
                    .isEqualTo(bare("rejected {} because {}", IBAN, new IllegalStateException("boom"))
                            .getFormattedMessage()
                            .replace(IBAN, MASKED_IBAN));
        }

        @Test
        @DisplayName("terminates on an array that points back at itself, and discloses nothing of it")
        void survivesASelfReferentialArray() {
            Object[] recursive = new Object[2];
            recursive[0] = IBAN;
            recursive[1] = recursive;

            ILoggingEvent masked = masker.mask(event("batch {}", (Object) recursive));

            assertThat(masked.getFormattedMessage()).doesNotContain(IBAN);
        }

        /** The line logback itself would have produced for the same arguments, minus the masking. */
        private static String logbackWouldRender(String message, Object... arguments) {
            return event(message, arguments).getFormattedMessage().replace(IBAN, MASKED_IBAN);
        }
    }

    @Nested
    @DisplayName("The message body")
    class MessageBody {

        @Test
        @DisplayName("masks an IBAN concatenated into the message, which has no argument to declare")
        void masksConcatenatedMessage() {
            ILoggingEvent masked = masker.mask(event("payment from " + IBAN + " accepted"));

            assertThat(masked.getFormattedMessage())
                    .doesNotContain(IBAN)
                    .isEqualTo("payment from " + MASKED_IBAN + " accepted");
        }

        @Test
        @DisplayName("masks the raw message as well, so a raw-message field is not a way around it")
        void masksTheRawMessage() {
            ILoggingEvent masked = masker.mask(event("payment from " + IBAN + " accepted"));

            assertThat(masked.getMessage()).doesNotContain(IBAN);
        }

        @Test
        @DisplayName("masks a value in the message template even when every argument was clean")
        void masksTemplateAroundCleanArguments() {
            ILoggingEvent masked = masker.mask(event("contact " + EMAIL + " about {}", "PMT-1"));

            assertThat(masked.getFormattedMessage())
                    .doesNotContain(EMAIL)
                    .isEqualTo("contact " + MASKED_EMAIL + " about PMT-1");
        }
    }

    @Nested
    @DisplayName("MDC")
    class Mdc {

        @Test
        @DisplayName("masks an MDC value, the leak that is attached to every line of a request")
        void masksMdcValue() {
            MDC.put("customer", EMAIL);

            ILoggingEvent masked = masker.mask(event("processing"));

            assertThat(masked.getMDCPropertyMap()).containsEntry("customer", MASKED_EMAIL);
        }

        @Test
        @DisplayName("leaves MDC keys alone, since a pattern or a filter refers to them by name")
        void keepsMdcKeys() {
            MDC.put("customer", EMAIL);
            MDC.put("requestId", "req-42");

            ILoggingEvent masked = masker.mask(event("processing"));

            assertThat(masked.getMDCPropertyMap()).containsKeys("customer", "requestId");
            assertThat(masked.getMDCPropertyMap()).containsEntry("requestId", "req-42");
        }

        @Test
        @DisplayName("reports the same map from the deprecated accessor, which a layout may still call")
        void masksTheDeprecatedAccessorToo() {
            MDC.put("customer", EMAIL);

            ILoggingEvent masked = masker.mask(event("processing"));

            assertThat(masked.getMdc()).isEqualTo(masked.getMDCPropertyMap());
        }
    }

    @Nested
    @DisplayName("Exceptions")
    class Exceptions {

        @Test
        @DisplayName("masks the row a constraint violation quoted back, the leak nobody looks for")
        void masksExceptionMessage() {
            ILoggingEvent masked = masker.mask(eventWith(
                    new IllegalStateException("Key (email)=(" + EMAIL + ") already exists"), "insert failed"));

            assertThat(masked.getThrowableProxy().getMessage())
                    .doesNotContain(EMAIL)
                    .contains(MASKED_EMAIL);
        }

        @Test
        @DisplayName("masks a cause further down the chain, which is where the driver's message ends up")
        void masksCauseMessage() {
            Throwable cause = new IllegalStateException("Key (iban)=(" + IBAN + ") already exists");

            ILoggingEvent masked =
                    masker.mask(eventWith(new RuntimeException("could not save", cause), "insert failed"));

            assertThat(masked.getThrowableProxy().getCause().getMessage())
                    .doesNotContain(IBAN)
                    .contains(MASKED_IBAN);
        }

        @Test
        @DisplayName("masks a suppressed exception, which is printed with the trace like any other")
        void masksSuppressedMessage() {
            RuntimeException thrown = new RuntimeException("commit failed");
            thrown.addSuppressed(new IllegalStateException("rollback of " + IBAN + " failed"));

            ILoggingEvent masked = masker.mask(eventWith(thrown, "transaction failed"));

            assertThat(masked.getThrowableProxy().getSuppressed()[0].getMessage())
                    .doesNotContain(IBAN)
                    .contains(MASKED_IBAN);
        }

        @Test
        @DisplayName("keeps the type and the frames, which identify code rather than a person")
        void keepsClassNameAndFrames() {
            ILoggingEvent masked =
                    masker.mask(eventWith(new IllegalStateException("Key (email)=(" + EMAIL + ")"), "insert failed"));

            assertThat(masked.getThrowableProxy().getClassName()).isEqualTo(IllegalStateException.class.getName());
            assertThat(masked.getThrowableProxy().getStackTraceElementProxyArray())
                    .isNotEmpty();
        }

        @Test
        @DisplayName("forwards an exception that carried nothing as the proxy logback built")
        void keepsACleanExceptionProxy() {
            LoggingEvent event = eventWith(new IllegalStateException("insufficient funds"), "payment failed");

            ILoggingEvent masked = masker.mask(event);

            assertThat(masked.getThrowableProxy()).isSameAs(event.getThrowableProxy());
        }
    }

    @Nested
    @DisplayName("Structured arguments")
    class KeyValuePairs {

        @Test
        @DisplayName("masks a key-value pair from the fluent API, which is an argument by another name")
        void masksKeyValuePair() {
            LoggingEvent event = event("payment accepted");
            event.addKeyValuePair(new KeyValuePair("iban", IBAN));

            ILoggingEvent masked = masker.mask(event);

            assertThat(masked.getKeyValuePairs().getFirst().value).isEqualTo(MASKED_IBAN);
            assertThat(masked.getKeyValuePairs().getFirst().key).isEqualTo("iban");
        }
    }

    @Nested
    @DisplayName("A line that carried nothing")
    class Clean {

        @Test
        @DisplayName("returns the same event, which is what keeps a PII-free line allocation-free")
        void returnsTheSameEvent() {
            MDC.put("requestId", "req-42");
            LoggingEvent event = event("payment {} accepted in {} ms", "PMT-1", 12);

            assertThat(masker.mask(event)).isSameAs(event);
        }

        @Test
        @DisplayName("returns the same event when it has no arguments, no MDC and no exception at all")
        void returnsTheSameBareEvent() {
            LoggingEvent event = event("started");

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

            observedBy(recorder).mask(event("payment from " + IBAN));

            assertThat(recorder.undeclared).containsExactly(ORIGIN + "/message:IBAN");
        }

        @Test
        @DisplayName("reports a detector hit in MDC with the key, so the code that set it can be found")
        void reportsUndeclaredPiiInMdc() {
            Recorder recorder = new Recorder();
            MDC.put("customer", EMAIL);

            observedBy(recorder).mask(event("processing"));

            assertThat(recorder.undeclared).containsExactly(ORIGIN + "/mdc/customer:EMAIL");
        }

        @Test
        @DisplayName("reports a declared argument field under the site it was logged at, not its own type")
        void reportsDeclaredMasking() {
            Recorder recorder = new Recorder();

            observedBy(recorder).mask(event("customer {}", customer()));

            assertThat(recorder.masked).contains(ORIGIN + "/arg0.iban:IBAN:IBAN");
        }

        @Test
        @DisplayName("numbers the argument, so a two-argument line says which one leaked")
        void reportsTheArgumentPosition() {
            Recorder recorder = new Recorder();

            observedBy(recorder).mask(event("payment {} from {}", "PMT-1", IBAN));

            assertThat(recorder.undeclared).containsExactly(ORIGIN + "/arg1:IBAN");
        }

        @Test
        @DisplayName("reports a key-value pair under its key, which is what the fluent API named it")
        void reportsKeyValuePairs() {
            Recorder recorder = new Recorder();
            LoggingEvent event = event("payment accepted");
            event.addKeyValuePair(new KeyValuePair("account", IBAN));

            observedBy(recorder).mask(event);

            assertThat(recorder.undeclared).containsExactly(ORIGIN + "/kv/account:IBAN");
        }

        @Test
        @DisplayName("reports an exception message, and a cause one level further down the chain")
        void reportsExceptionsAndTheirCauses() {
            Recorder recorder = new Recorder();
            Throwable cause = new IllegalStateException("Key (iban)=(" + IBAN + ") already exists");

            observedBy(recorder).mask(eventWith(new RuntimeException("could not save " + EMAIL, cause), "insert"));

            assertThat(recorder.undeclared).contains(ORIGIN + "/throwable:EMAIL", ORIGIN + "/throwable/cause:IBAN");
        }

        @Test
        @DisplayName("reports a suppressed exception under the container and its index")
        void reportsSuppressedExceptions() {
            Recorder recorder = new Recorder();
            RuntimeException thrown = new RuntimeException("commit failed");
            thrown.addSuppressed(new IllegalStateException("rollback of " + IBAN + " failed"));

            observedBy(recorder).mask(eventWith(thrown, "transaction failed"));

            assertThat(recorder.undeclared).containsExactly(ORIGIN + "/throwable/suppressed/0:IBAN");
        }

        @Test
        @DisplayName("reports a marker payload under the field name the encoder will write it as")
        void reportsMarkerPayloads() {
            Recorder recorder = new Recorder();
            LoggingEvent event = event("payment received");
            event.addMarker(net.logstash.logback.marker.Markers.append("account", IBAN));

            observedBy(recorder).mask(event);

            assertThat(recorder.undeclared).containsExactly(ORIGIN + "/marker/account:IBAN");
        }

        @Test
        @DisplayName("reports a nested marker under the marker it hangs from, slash by slash")
        void reportsNestedMarkerPayloads() {
            Recorder recorder = new Recorder();
            org.slf4j.Marker filtering = org.slf4j.MarkerFactory.getDetachedMarker("AUDIT");
            filtering.add(net.logstash.logback.marker.Markers.append("account", IBAN));
            LoggingEvent event = event("payment received");
            event.addMarker(filtering);

            observedBy(recorder).mask(event);

            assertThat(recorder.undeclared).containsExactly(ORIGIN + "/marker/AUDIT/account:IBAN");
        }

        @Test
        @DisplayName("prefixes every path it reports with the module's scheme, so a SIEM can tell sources apart")
        void everyPathCarriesTheScheme() {
            Recorder recorder = new Recorder();
            MDC.put("customer", EMAIL);
            LoggingEvent event = event("payment from " + IBAN + " for {}", customer());
            event.addKeyValuePair(new KeyValuePair("account", IBAN));
            event.addMarker(net.logstash.logback.marker.Markers.append("account", IBAN));

            observedBy(recorder).mask(event);

            assertThat(recorder.everything())
                    .isNotEmpty()
                    .allSatisfy(path -> assertThat(path).startsWith(ORIGIN + "/"));
        }
    }

    @Nested
    @DisplayName("Limits")
    class Limits {

        @Test
        @DisplayName("reports a cut suppressed list as a truncation, with the container's path and what it kept")
        void reportsTruncationOfTheSuppressedList() {
            Recorder recorder = new Recorder();
            RuntimeException thrown = new RuntimeException("commit failed");
            thrown.addSuppressed(new IllegalStateException("first " + IBAN));
            thrown.addSuppressed(new IllegalStateException("second " + IBAN));
            thrown.addSuppressed(new IllegalStateException("third " + IBAN));

            ILoggingEvent masked = boundedTo(2, recorder).mask(eventWith(thrown, "transaction failed"));

            assertThat(recorder.truncated).containsExactly(ORIGIN + "/throwable/suppressed:2");
            assertThat(recorder.depthExceeded).isEmpty();
            assertThat(masked.getThrowableProxy().getSuppressed()).hasSize(2);
        }

        @Test
        @DisplayName("keeps the dropped tail out of the masked event entirely, rather than passing it through")
        void dropsTheTailOfATruncatedSuppressedList() {
            Recorder recorder = new Recorder();
            RuntimeException thrown = new RuntimeException("commit failed");
            thrown.addSuppressed(new IllegalStateException("first failure"));
            thrown.addSuppressed(new IllegalStateException("rollback of " + IBAN + " failed"));

            ILoggingEvent masked = boundedTo(1, recorder).mask(eventWith(thrown, "transaction failed"));

            assertThat(masked.getThrowableProxy().getSuppressed()).hasSize(1);
            assertThat(masked.getThrowableProxy().getSuppressed()[0].getMessage())
                    .doesNotContain(IBAN);
        }

        @Test
        @DisplayName("reports a marker graph that ran too deep as a depth limit, which is what it is")
        void reportsMarkerDepthAsDepth() {
            Recorder recorder = new Recorder();
            org.slf4j.Marker outer = org.slf4j.MarkerFactory.getDetachedMarker("OUTER");
            org.slf4j.Marker inner = org.slf4j.MarkerFactory.getDetachedMarker("INNER");
            outer.add(inner);
            inner.add(net.logstash.logback.marker.Markers.append("account", IBAN));
            LoggingEvent event = event("payment received");
            event.addMarker(outer);

            ILoggingEvent masked = shallow(recorder).mask(event);

            assertThat(recorder.depthExceeded).containsExactly(ORIGIN + "/marker/OUTER/INNER/account");
            assertThat(recorder.truncated).isEmpty();
            assertThat(encode(masked)).doesNotContain(IBAN);
        }

        private LoggingEventMasker boundedTo(int elements, MaskingObserver observer) {
            return new LoggingEventMasker(DataMask.builder()
                    .secret(SECRET)
                    .observer(observer)
                    .policy(MaskingPolicy.strict().withMaxCollectionElements(elements))
                    .build());
        }

        private LoggingEventMasker shallow(MaskingObserver observer) {
            return new LoggingEventMasker(DataMask.builder()
                    .secret(SECRET)
                    .observer(observer)
                    .policy(MaskingPolicy.strict().withMaxDepth(1))
                    .build());
        }
    }

    @Nested
    @DisplayName("Failing closed")
    class FailingClosed {

        @Test
        @DisplayName("renders the placeholder when a masker throws, never the value it failed to mask")
        void redactsWhenTheMaskerThrows() {
            ILoggingEvent masked = masker.mask(event("checking {}", new Banking.Fragile(IBAN)));

            assertThat(masked.getFormattedMessage()).doesNotContain(IBAN).contains("****");
        }

        @Test
        @DisplayName(
                "withholds the message rather than throwing under FailureMode.THROW, since a log call must not fail")
        void withholdsUnderThrow() {
            DataMask throwing = DataMask.builder()
                    .secret(SECRET)
                    .policy(MaskingPolicy.strict().withFailureMode(FailureMode.THROW))
                    .build();
            MDC.put("customer", EMAIL);

            ILoggingEvent masked =
                    new LoggingEventMasker(throwing).mask(event("checking {}", new Banking.Fragile(IBAN)));

            assertThat(masked.getFormattedMessage()).doesNotContain(IBAN).contains("withheld");
            assertThat(masked.getMDCPropertyMap()).isEmpty();
            assertThat(masked.getThrowableProxy()).isNull();
        }

        @Test
        @DisplayName("keeps the level, the logger and the timestamp of a withheld event, so the line is still there")
        void withheldEventKeepsItsFrame() {
            DataMask throwing = DataMask.builder()
                    .secret(SECRET)
                    .policy(MaskingPolicy.strict().withFailureMode(FailureMode.THROW))
                    .build();
            LoggingEvent event = event("checking {}", new Banking.Fragile(IBAN));

            ILoggingEvent masked = new LoggingEventMasker(throwing).mask(event);

            assertThat(masked.getLevel()).isEqualTo(Level.INFO);
            assertThat(masked.getLoggerName()).isEqualTo(LOGGER);
            assertThat(masked.getTimeStamp()).isEqualTo(event.getTimeStamp());
        }

        @Test
        @DisplayName("reports the failure to the observer, without the value in the path it reports")
        void reportsTheFailure() {
            Recorder recorder = new Recorder();

            throwingMasker(recorder).mask(event("checking {}", new Banking.Fragile(IBAN)));

            assertThat(recorder.failures)
                    .isNotEmpty()
                    .allSatisfy(path -> assertThat(path).doesNotContain(IBAN).startsWith(ORIGIN + "/"));
        }

        @Test
        @DisplayName("reports a withheld event against the event itself, not against a bare logger name")
        void reportsTheWithheldEventUnderTheEventSite() {
            Recorder recorder = new Recorder();

            throwingMasker(recorder).mask(event("checking {}", new Banking.Fragile(IBAN)));

            assertThat(recorder.failures).contains(ORIGIN + "/event");
        }

        private LoggingEventMasker throwingMasker(MaskingObserver observer) {
            return new LoggingEventMasker(DataMask.builder()
                    .secret(SECRET)
                    .observer(observer)
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
            ILoggingEvent masked = quiet().mask(event("payment from " + IBAN));

            assertThat(masked.getFormattedMessage()).contains(IBAN);
        }

        @Test
        @DisplayName("still masks a declared field with scanning off, because that was declared, not detected")
        void stillMasksDeclaredFields() {
            ILoggingEvent masked = quiet().mask(event("customer {}", customer()));

            assertThat(masked.getFormattedMessage()).doesNotContain(IBAN);
        }

        @Test
        @DisplayName("leaves MDC and exception messages alone with scanning off, the two it can only detect")
        void leavesMdcAndExceptionsAloneWithScanningOff() {
            MDC.put("customer", EMAIL);

            ILoggingEvent masked = quiet().mask(eventWith(new IllegalStateException("Key (iban)=(" + IBAN + ")"), "x"));

            assertThat(masked.getMDCPropertyMap()).containsEntry("customer", EMAIL);
            assertThat(masked.getThrowableProxy().getMessage()).contains(IBAN);
        }

        @Test
        @DisplayName("produces the same pseudonym for the same value, so a customer stays traceable across lines")
        void hashesConsistently() {
            String first = masker.mask(event("ref {}", new Banking.Reference("customer-7")))
                    .getFormattedMessage();
            String second = masker.mask(event("ref {}", new Banking.Reference("customer-7")))
                    .getFormattedMessage();

            assertThat(first).doesNotContain("customer-7").isEqualTo(second);
        }

        private LoggingEventMasker quiet() {
            return new LoggingEventMasker(DataMask.builder()
                    .secret(SECRET)
                    .policy(MaskingPolicy.strict().withScanUnannotatedText(false))
                    .build());
        }
    }

    private LoggingEventMasker observedBy(MaskingObserver observer) {
        return new LoggingEventMasker(
                DataMask.builder().secret(SECRET).observer(observer).build());
    }

    @Nested
    @DisplayName("Markers")
    class Markers {

        @Test
        @DisplayName("masks an object shipped on a logstash marker, which the encoder writes into the JSON")
        void masksLogstashAppendedObject() {
            LoggingEvent event = event("payment received");
            event.addMarker(net.logstash.logback.marker.Markers.append("customer", customer()));

            String json = encode(masker.mask(event));

            assertThat(json)
                    .doesNotContain(IBAN)
                    .doesNotContain(EMAIL)
                    .contains(MASKED_IBAN)
                    .contains(MASKED_EMAIL);
        }

        @Test
        @DisplayName("masks the entries of an appended map")
        void masksLogstashAppendedEntries() {
            LoggingEvent event = event("payment received");
            event.addMarker(
                    net.logstash.logback.marker.Markers.appendEntries(java.util.Map.of("iban", IBAN, "country", "CH")));

            String json = encode(masker.mask(event));

            assertThat(json).doesNotContain(IBAN).contains("CH");
        }

        @Test
        @DisplayName("masks a logstash marker attached as a child of a filtering marker")
        void masksNestedLogstashMarker() {
            org.slf4j.Marker filtering = org.slf4j.MarkerFactory.getDetachedMarker("AUDIT");
            filtering.add(net.logstash.logback.marker.Markers.append("customer", customer()));
            LoggingEvent event = event("payment received");
            event.addMarker(filtering);

            String json = encode(masker.mask(event));

            assertThat(json).doesNotContain(IBAN).doesNotContain(EMAIL).contains(MASKED_IBAN);
        }

        @Test
        @DisplayName("leaves a plain filtering marker alone, so marker-based filters keep working")
        void keepsPlainMarkers() {
            LoggingEvent event = event("payment received");
            event.addMarker(org.slf4j.MarkerFactory.getMarker("AUDIT"));

            ILoggingEvent masked = masker.mask(event);

            assertThat(masked.getMarkerList()).isSameAs(event.getMarkerList());
            assertThat(masked).isSameAs(event);
        }

        @Test
        @DisplayName("returns the very same event when nothing, markers included, carried PII")
        void keepsCleanEventsIntact() {
            LoggingEvent event = event("payment received");

            assertThat(masker.mask(event)).isSameAs(event);
        }

        @Test
        @DisplayName("strips the payload of a marker type it cannot inspect rather than forwarding it")
        void stripsUnknownMarkerTypes() {
            LoggingEvent event = event("payment received");
            event.addMarker(new LeakyMarker("AUDIT", IBAN));

            ILoggingEvent masked = masker.mask(event);

            assertThat(masked.getMarkerList()).hasSize(1);
            assertThat(masked.getMarkerList().getFirst().getName()).isEqualTo("AUDIT");
            assertThat(encode(masked)).doesNotContain(IBAN);
        }

        @Test
        @DisplayName("redacts a marker whose payload masking failed, instead of passing it through")
        void redactsMarkersThatFailToMask() {
            Recorder recorder = new Recorder();
            LoggingEventMasker throwing = new LoggingEventMasker(DataMask.builder()
                    .secret(SECRET)
                    .policy(MaskingPolicy.strict().withFailureMode(FailureMode.THROW))
                    .observer(recorder)
                    .build());
            LoggingEvent event = event("payment received");
            event.addMarker(net.logstash.logback.marker.Markers.append("secret", new Banking.Fragile(IBAN)));

            String json = encode(throwing.mask(event));

            assertThat(json).doesNotContain(IBAN);
            assertThat(recorder.failures).isNotEmpty();
        }

        /** A marker type from outside this module whose payload only surfaces through toString(). */
        private record LeakyMarker(String name, String payload) implements org.slf4j.Marker {

            @Override
            public String getName() {
                return name;
            }

            @Override
            public String toString() {
                return name + "=" + payload;
            }

            @Override
            public void add(org.slf4j.Marker reference) {}

            @Override
            public boolean remove(org.slf4j.Marker reference) {
                return false;
            }

            @Override
            public boolean hasReferences() {
                return false;
            }

            @Override
            @Deprecated
            public boolean hasChildren() {
                return false;
            }

            @Override
            public java.util.Iterator<org.slf4j.Marker> iterator() {
                return java.util.Collections.emptyIterator();
            }

            @Override
            public boolean contains(org.slf4j.Marker other) {
                return false;
            }

            @Override
            public boolean contains(String otherName) {
                return false;
            }
        }
    }

    /** What a JSON stack actually ships, which is where a marker payload would surface. */
    private static String encode(ILoggingEvent event) {
        net.logstash.logback.encoder.LogstashEncoder encoder = new net.logstash.logback.encoder.LogstashEncoder();
        encoder.setContext(CONTEXT);
        encoder.start();
        try {
            return new String(encoder.encode(event), java.nio.charset.StandardCharsets.UTF_8);
        } finally {
            encoder.stop();
        }
    }

    private static Banking.Customer customer() {
        return new Banking.Customer(new Banking.Email(EMAIL), IBAN, "CH");
    }

    private static LoggingEvent event(String message, Object... arguments) {
        return new LoggingEvent(
                LoggingEventMaskerTest.class.getName(),
                CONTEXT.getLogger(LOGGER),
                Level.INFO,
                message,
                null,
                arguments.length == 0 ? null : arguments);
    }

    /**
     * An event assembled field by field rather than through the constructor, which is what leaves a
     * throwable in the argument array without a proxy of its own.
     */
    private static LoggingEvent bare(String message, Object... arguments) {
        LoggingEvent event = new LoggingEvent();
        event.setLoggerName(LOGGER);
        event.setLevel(Level.INFO);
        event.setMessage(message);
        event.setArgumentArray(arguments);
        event.setMDCPropertyMap(java.util.Map.of());
        return event;
    }

    private static LoggingEvent eventWith(Throwable thrown, String message) {
        return new LoggingEvent(
                LoggingEventMaskerTest.class.getName(), CONTEXT.getLogger(LOGGER), Level.INFO, message, thrown, null);
    }

    private static final class Recorder implements MaskingObserver {

        private final List<String> masked = new ArrayList<>();
        private final List<String> undeclared = new ArrayList<>();
        private final List<String> scanned = new ArrayList<>();
        private final List<String> failures = new ArrayList<>();
        private final List<String> depthExceeded = new ArrayList<>();
        private final List<String> truncated = new ArrayList<>();

        /** Every path this observer was handed, whatever the signal, for a grammar-wide assertion. */
        private final List<String> paths = new ArrayList<>();

        List<String> everything() {
            return paths;
        }

        @Override
        public void onMasked(String path, PiiCategory category, MaskStrategy strategy) {
            paths.add(path);
            masked.add(path + ":" + category + ":" + strategy);
        }

        @Override
        public void onUnannotatedPii(String path, PiiCategory category, String detector) {
            paths.add(path);
            undeclared.add(path + ":" + category);
        }

        @Override
        public void onScanned(String path, PiiCategory category, String detector) {
            paths.add(path);
            scanned.add(path + ":" + category);
        }

        @Override
        public void onFailure(String path, Throwable error) {
            paths.add(path);
            failures.add(path);
        }

        @Override
        public void onDepthLimitExceeded(String path) {
            paths.add(path);
            depthExceeded.add(path);
        }

        @Override
        public void onCollectionTruncated(String path, int kept) {
            paths.add(path);
            truncated.add(path + ":" + kept);
        }
    }
}
