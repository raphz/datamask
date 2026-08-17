package ch.raph.datamask.processor;

import static ch.raph.datamask.processor.Compilation.source;
import static org.assertj.core.api.Assertions.assertThat;

import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("The @PII annotation processor")
class PiiProcessorTest {

    private static final JavaFileObject MASKERS = source("fixture.Maskers", """
            package fixture;

            import ch.raph.datamask.api.MaskContext;
            import ch.raph.datamask.api.Masker;

            public class Maskers {

                public static class Wellformed implements Masker {
                    public Object mask(Object value, MaskContext context) {
                        return "****";
                    }
                }

                public static class NeedsArguments implements Masker {
                    private final String prefix;

                    public NeedsArguments(String prefix) {
                        this.prefix = prefix;
                    }

                    public Object mask(Object value, MaskContext context) {
                        return prefix;
                    }
                }

                public abstract static class Abstract implements Masker {}

                public class Inner implements Masker {
                    public Object mask(Object value, MaskContext context) {
                        return "****";
                    }
                }

                static class PackagePrivate implements Masker {
                    public Object mask(Object value, MaskContext context) {
                        return "****";
                    }
                }

                public static class HiddenConstructor implements Masker {
                    private HiddenConstructor() {}

                    public Object mask(Object value, MaskContext context) {
                        return "****";
                    }
                }
            }
            """);

    /**
     * Lombok is not a dependency of this module, and a test that depends on it running would be
     * testing Lombok rather than this check. The annotation alone is what the check looks for.
     */
    private static final JavaFileObject LOMBOK = source("lombok.AllArgsConstructor", """
            package lombok;

            public @interface AllArgsConstructor {}
            """);

    private static Compilation.Result compile(String name, String code) {
        return Compilation.of(MASKERS, source(name, code)).run();
    }

    private static Compilation.Result compileLenient(String name, String code) {
        return Compilation.of(MASKERS, source(name, code))
                .withOption("-Adatamask.strict=false")
                .run();
    }

    @Nested
    @DisplayName("given a custom masker the engine would have to instantiate")
    class CustomMaskers {

        @Test
        @DisplayName("fails the build when the masker has no no-argument constructor, naming the field and the class")
        void rejectsAMaskerWithoutANoArgumentConstructor() {
            var result = compile("fixture.Account", """
                    package fixture;

                    import ch.raph.datamask.api.PII;

                    public record Account(@PII(masker = Maskers.NeedsArguments.class) String reference) {}
                    """);

            assertThat(result.errors())
                    .singleElement()
                    .asString()
                    .contains("Account.reference")
                    .contains("fixture.Maskers.NeedsArguments")
                    .contains("no no-argument constructor")
                    .contains("DataMask.builder().masker(new NeedsArguments(...))");
        }

        @Test
        @DisplayName("fails the build when the masker is abstract, because reflection cannot instantiate one")
        void rejectsAnAbstractMasker() {
            var result = compile("fixture.Account", """
                    package fixture;

                    import ch.raph.datamask.api.PII;

                    public record Account(@PII(masker = Maskers.Abstract.class) String reference) {}
                    """);

            assertThat(result.errors()).singleElement().asString().contains("is abstract");
        }

        @Test
        @DisplayName("fails the build for an inner class, whose constructor secretly takes its enclosing instance")
        void rejectsAnInnerClassMasker() {
            var result = compile("fixture.Account", """
                    package fixture;

                    import ch.raph.datamask.api.PII;

                    public record Account(@PII(masker = Maskers.Inner.class) String reference) {}
                    """);

            assertThat(result.errors()).singleElement().asString().contains("is an inner class");
        }

        @Test
        @DisplayName("fails the build when the masker is not public, since the engine cannot see it to build it")
        void rejectsAMaskerThatIsNotPublic() {
            var result = compile("fixture.Account", """
                    package fixture;

                    import ch.raph.datamask.api.PII;

                    public record Account(@PII(masker = Maskers.PackagePrivate.class) String reference) {}
                    """);

            assertThat(result.errors()).singleElement().asString().contains("is not visible to the engine");
        }

        @Test
        @DisplayName("fails the build when the no-argument constructor exists but is private")
        void rejectsAPrivateNoArgumentConstructor() {
            var result = compile("fixture.Account", """
                    package fixture;

                    import ch.raph.datamask.api.PII;

                    public record Account(@PII(masker = Maskers.HiddenConstructor.class) String reference) {}
                    """);

            assertThat(result.errors())
                    .singleElement()
                    .asString()
                    .contains("no-argument constructor of fixture.Maskers.HiddenConstructor is not public");
        }

        @Test
        @DisplayName("accepts a public masker with an implicit no-argument constructor")
        void acceptsAWellformedMasker() {
            var result = compile("fixture.Account", """
                    package fixture;

                    import ch.raph.datamask.api.PII;

                    public record Account(@PII(masker = Maskers.Wellformed.class) String reference) {}
                    """);

            assertThat(result.all()).isEmpty();
        }

        @Test
        @DisplayName("reports a record component once, although javac copies the annotation onto four elements")
        void reportsARecordComponentOnlyOnce() {
            var result = compile("fixture.Account", """
                    package fixture;

                    import ch.raph.datamask.api.PII;

                    public record Account(@PII(masker = Maskers.NeedsArguments.class) String reference) {}
                    """);

            assertThat(result.all()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("given a class that must be rebuilt once its values are masked")
    class RebuildableTypes {

        @Test
        @DisplayName("fails the build naming the constructor that is missing, not merely that one is")
        void namesTheConstructorThatWouldMakeTheClassRebuildable() {
            var result = compile("fixture.Customer", """
                    package fixture;

                    import ch.raph.datamask.api.PII;

                    public class Customer {
                        @PII private String email;
                        private int age;

                        public Customer(String email, int age, String unrelated) {}
                    }
                    """);

            assertThat(result.errors())
                    .singleElement()
                    .asString()
                    .contains("fixture.Customer")
                    .contains("Customer(String, int)")
                    .contains("(email, age)")
                    .contains("no no-argument constructor");
        }

        @Test
        @DisplayName("accepts a bean with an all-arguments constructor matching the field order")
        void acceptsAnAllArgumentsConstructor() {
            var result = compile("fixture.Customer", """
                    package fixture;

                    import ch.raph.datamask.api.PII;

                    public class Customer {
                        @PII private String email;
                        private int age;

                        public Customer(String email, int age) {}
                    }
                    """);

            assertThat(result.all()).isEmpty();
        }

        @Test
        @DisplayName("accepts a bean whose only constructor takes no arguments, which the engine follows with writes")
        void acceptsANoArgumentConstructor() {
            var result = compile("fixture.Customer", """
                    package fixture;

                    import ch.raph.datamask.api.PII;

                    public class Customer {
                        @PII private String email;

                        public Customer() {}

                        public Customer(String email, String extra) {}
                    }
                    """);

            assertThat(result.all()).isEmpty();
        }

        @Test
        @DisplayName("fails the build on final fields with only a no-argument constructor, which the engine "
                + "cannot write to however ordinary the class looks")
        void rejectsFinalFieldsWithOnlyANoArgumentConstructor() {
            var result = compile("fixture.Customer", """
                    package fixture;

                    import ch.raph.datamask.api.PII;

                    public class Customer {
                        @PII private final String email;
                        private int age;

                        public Customer() {
                            this.email = null;
                        }
                    }
                    """);

            assertThat(result.errors())
                    .singleElement()
                    .asString()
                    .contains("fixture.Customer")
                    .contains("email is final")
                    .contains("Customer(String, int)");
        }

        @Test
        @DisplayName("accepts a constructor whose parameters name the fields out of order, which the engine "
                + "matches by name and permutes")
        void acceptsAConstructorThatNamesTheFieldsOutOfOrder() {
            var result = compile("fixture.Customer", """
                    package fixture;

                    import ch.raph.datamask.api.PII;

                    public class Customer {
                        @PII private String email;
                        private int age;

                        public Customer(int age, String email) {}
                    }
                    """);

            assertThat(result.all()).isEmpty();
        }

        @Test
        @DisplayName("accepts a record, which always rebuilds through its canonical constructor")
        void acceptsARecord() {
            var result = compile("fixture.Customer", """
                    package fixture;

                    import ch.raph.datamask.api.PII;

                    public record Customer(@PII String email, String country) {}
                    """);

            assertThat(result.all()).isEmpty();
        }

        @Test
        @DisplayName("counts inherited fields, because the engine masks them too")
        void countsInheritedFields() {
            var result = compile("fixture.Customer", """
                    package fixture;

                    import ch.raph.datamask.api.PII;

                    class Person {
                        private String country;
                    }

                    public class Customer extends Person {
                        @PII private String email;

                        public Customer(String email) {}
                    }
                    """);

            assertThat(result.errors()).singleElement().asString().contains("Customer(String, String)");
        }

        @Test
        @DisplayName("fails the build for an inner class, which has no constructor the engine can call")
        void rejectsAnInnerClass() {
            var result = compile("fixture.Outer", """
                    package fixture;

                    import ch.raph.datamask.api.PII;

                    public class Outer {
                        public class Customer {
                            @PII private String email;
                        }
                    }
                    """);

            assertThat(result.errors()).singleElement().asString().contains("is an inner class");
        }

        @Test
        @DisplayName("says nothing about a Lombok class, whose constructors are generated after this runs")
        void staysSilentOnLombokAnnotatedTypes() {
            var result = Compilation.of(LOMBOK, source("fixture.Customer", """
                    package fixture;

                    import ch.raph.datamask.api.PII;

                    @lombok.AllArgsConstructor
                    public class Customer {
                        @PII private String email;
                        private int age;
                    }
                    """)).run();

            assertThat(result.all()).isEmpty();
        }

        @Test
        @DisplayName("says nothing about an abstract class, whose concrete subclass is what gets rebuilt")
        void staysSilentOnAbstractClasses() {
            var result = compile("fixture.Customer", """
                    package fixture;

                    import ch.raph.datamask.api.PII;

                    public abstract class Customer {
                        @PII private String email;

                        protected Customer(String email, String extra) {}
                    }
                    """);

            assertThat(result.all()).isEmpty();
        }
    }

    @Nested
    @DisplayName("given a @NoMask exemption")
    class Justifications {

        @Test
        @DisplayName("fails the build on a blank justification, the one annotation that reveals rather than masks")
        void rejectsABlankJustification() {
            var result = compile("fixture.Customer", """
                    package fixture;

                    import ch.raph.datamask.api.NoMask;

                    public record Customer(@NoMask(justification = "   ") String country) {}
                    """);

            assertThat(result.errors())
                    .singleElement()
                    .asString()
                    .contains("Customer.country")
                    .contains("the justification is blank");
        }

        @Test
        @DisplayName("warns when the justification is a placeholder, which is what a reviewer would miss")
        void warnsOnAPlaceholderJustification() {
            var result = compile("fixture.Customer", """
                    package fixture;

                    import ch.raph.datamask.api.NoMask;

                    public record Customer(@NoMask(justification = "TODO") String country) {}
                    """);

            assertThat(result.errors()).isEmpty();
            assertThat(result.warnings()).singleElement().asString().contains("reads as a placeholder");
        }

        @Test
        @DisplayName("accepts a justification that states why the value is safe to disclose")
        void acceptsARealJustification() {
            var result = compile("fixture.Customer", """
                    package fixture;

                    import ch.raph.datamask.api.NoMask;

                    public record Customer(
                            @NoMask(justification = "ISO country code, not identifying on its own") String country) {}
                    """);

            assertThat(result.all()).isEmpty();
        }

        @Test
        @DisplayName("checks an exemption written on a hand-written record accessor, which is a declaration and "
                + "not one of javac's copies")
        void checksAnExemptionOnAHandWrittenAccessor() {
            var result = compile("fixture.Customer", """
                    package fixture;

                    import ch.raph.datamask.api.NoMask;

                    public record Customer(String country) {

                        @NoMask(justification = "   ")
                        @Override
                        public String country() {
                            return country;
                        }
                    }
                    """);

            assertThat(result.errors())
                    .singleElement()
                    .asString()
                    .contains("Customer.country")
                    .contains("the justification is blank");
        }

        @Test
        @DisplayName("still reports a component's own exemption once, although javac copies it onto the field, "
                + "the accessor and the constructor parameter")
        void reportsAComponentExemptionOnlyOnce() {
            var result = compile("fixture.Customer", """
                    package fixture;

                    import ch.raph.datamask.api.NoMask;

                    public record Customer(@NoMask(justification = "TODO") String country) {}
                    """);

            assertThat(result.warnings()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("given a @PII that does not mask the value it is written on")
    class IneffectiveDeclarations {

        @Test
        @DisplayName("fails the build when the same member also carries @NoMask, because the exemption wins and "
                + "the annotation reads as protection while removing it")
        void rejectsPiiExemptedOnTheSameMember() {
            var result = compile("fixture.Customer", """
                    package fixture;

                    import ch.raph.datamask.api.NoMask;
                    import ch.raph.datamask.api.PII;

                    public record Customer(
                            @PII @NoMask(justification = "already redacted upstream") String email) {}
                    """);

            assertThat(result.errors())
                    .singleElement()
                    .asString()
                    .contains("Customer.email")
                    .contains("also carries @NoMask")
                    .contains("clear text");
        }

        @Test
        @DisplayName("and when the exemption is on the getter rather than on the field, which is the same "
                + "resolution the runtime performs and the harder one to see in a review")
        void rejectsPiiExemptedOnTheGetter() {
            var result = compile("fixture.Customer", """
                    package fixture;

                    import ch.raph.datamask.api.NoMask;
                    import ch.raph.datamask.api.PII;

                    public class Customer {
                        @PII private String email;

                        @NoMask(justification = "already redacted upstream")
                        public String getEmail() {
                            return email;
                        }

                        public Customer(String email) {}
                    }
                    """);

            assertThat(result.errors())
                    .singleElement()
                    .asString()
                    .contains("Customer.email")
                    .contains("getEmail()")
                    .contains("also carries @NoMask");
        }

        @Test
        @DisplayName("warns on a static field, which neither plan compiler ever reads, so the annotation is dead "
                + "code that looks like protection")
        void warnsOnAStaticField() {
            var result = compile("fixture.Customer", """
                    package fixture;

                    import ch.raph.datamask.api.PII;

                    public class Customer {
                        @PII static String lastSeenEmail;
                    }
                    """);

            assertThat(result.errors()).isEmpty();
            assertThat(result.warnings())
                    .singleElement()
                    .asString()
                    .contains("Customer.lastSeenEmail")
                    .contains("the field is static");
        }

        @Test
        @DisplayName("says nothing when a @NoMask sits on a different member of the same class")
        void staysSilentOnAnExemptionElsewhere() {
            var result = compile("fixture.Customer", """
                    package fixture;

                    import ch.raph.datamask.api.NoMask;
                    import ch.raph.datamask.api.PII;

                    public record Customer(
                            @PII String email,
                            @NoMask(justification = "ISO country code, not identifying") String country) {}
                    """);

            assertThat(result.all()).isEmpty();
        }
    }

    @Nested
    @DisplayName("given Gradle asking what kind of processor this is")
    class IncrementalBuilds {

        /**
         * Without the declaration Gradle assumes the worst and recompiles every source file in the
         * project on every change, in every build that puts this module on its annotation path. The
         * cost lands on adopters, so nothing in this repository would ever notice it.
         */
        @Test
        @DisplayName("declares itself isolating, so adding the processor does not cost every adopter their "
                + "incremental compilation")
        void declaresItselfIsolating() throws Exception {
            var resource = PiiProcessor.class
                    .getClassLoader()
                    .getResourceAsStream("META-INF/gradle/incremental.annotation.processors");

            assertThat(resource)
                    .as("META-INF/gradle/incremental.annotation.processors has to be on the class path")
                    .isNotNull();
            try (var stream = resource) {
                assertThat(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                                .lines()
                                .filter(line -> !line.startsWith("#"))
                                .filter(line -> !line.isBlank())
                                .toList())
                        .containsExactly(PiiProcessor.class.getName() + ",ISOLATING");
            }
        }

        @Test
        @DisplayName("and declares every processor it registers, because one undeclared entry turns the whole "
                + "compile task non-incremental")
        void declaresEveryRegisteredProcessor() throws Exception {
            var services = PiiProcessor.class
                    .getClassLoader()
                    .getResourceAsStream("META-INF/services/javax.annotation.processing.Processor");

            assertThat(services).isNotNull();
            try (var stream = services) {
                assertThat(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                                .lines()
                                .filter(line -> !line.isBlank())
                                .toList())
                        .containsExactly(PiiProcessor.class.getName());
            }
        }
    }

    @Nested
    @DisplayName("given a category that is never partially revealed")
    class NeverPartiallyRevealed {

        @Test
        @DisplayName("warns that keep is silently forced to zero, so the declaration stops claiming otherwise")
        void warnsThatKeepIsIgnored() {
            var result = compile("fixture.Card", """
                    package fixture;

                    import ch.raph.datamask.api.PII;
                    import ch.raph.datamask.api.PiiCategory;

                    public record Card(
                            @PII(category = PiiCategory.CARD_VERIFICATION_VALUE, keep = 3) String cvv) {}
                    """);

            assertThat(result.errors()).isEmpty();
            assertThat(result.warnings())
                    .singleElement()
                    .asString()
                    .contains("Card.cvv")
                    .contains("keep = 3")
                    .contains("CARD_VERIFICATION_VALUE");
        }

        @Test
        @DisplayName("warns for credentials too, not only for card data")
        void warnsForCredentials() {
            var result = compile("fixture.Session", """
                    package fixture;

                    import ch.raph.datamask.api.PII;
                    import ch.raph.datamask.api.PiiCategory;

                    public record Session(@PII(category = PiiCategory.CREDENTIAL, keep = 4) String token) {}
                    """);

            assertThat(result.warnings()).singleElement().asString().contains("CREDENTIAL");
        }

        @Test
        @DisplayName("says nothing when keep is left alone, which is the overwhelmingly common case")
        void staysSilentWithoutKeep() {
            var result = compile("fixture.Card", """
                    package fixture;

                    import ch.raph.datamask.api.PII;
                    import ch.raph.datamask.api.PiiCategory;

                    public record Card(@PII(category = PiiCategory.CARD_VERIFICATION_VALUE) String cvv) {}
                    """);

            assertThat(result.all()).isEmpty();
        }

        @Test
        @DisplayName("says nothing about keep on a category that may be partially revealed")
        void staysSilentOnCategoriesThatMayBePartiallyRevealed() {
            var result = compile("fixture.Account", """
                    package fixture;

                    import ch.raph.datamask.api.PII;
                    import ch.raph.datamask.api.PiiCategory;

                    public record Account(@PII(category = PiiCategory.IBAN, keep = 4) String iban) {}
                    """);

            assertThat(result.all()).isEmpty();
        }
    }

    @Nested
    @DisplayName("given -Adatamask.strict=false")
    class Lenient {

        @Test
        @DisplayName("reports the same findings as warnings, so a codebase can adopt the processor before fixing")
        void downgradesErrorsToWarnings() {
            var result = compileLenient("fixture.Account", """
                    package fixture;

                    import ch.raph.datamask.api.PII;

                    public record Account(@PII(masker = Maskers.NeedsArguments.class) String reference) {}
                    """);

            assertThat(result.errors()).isEmpty();
            assertThat(result.warnings()).singleElement().asString().contains("no no-argument constructor");
        }
    }

    @Nested
    @DisplayName("given a domain that uses the annotations as intended")
    class CleanDomain {

        @Test
        @DisplayName("says nothing at all, because a check that fires on correct code gets switched off")
        void staysSilent() {
            var result = compile("fixture.Banking", """
                    package fixture;

                    import ch.raph.datamask.api.MaskStrategy;
                    import ch.raph.datamask.api.NoMask;
                    import ch.raph.datamask.api.PII;
                    import ch.raph.datamask.api.PiiCategory;
                    import java.util.List;

                    public class Banking {

                        @PII(category = PiiCategory.EMAIL)
                        public record Email(String value) {}

                        public record Customer(
                                @PII Email email,
                                @PII(strategy = MaskStrategy.HASH) String iban,
                                @PII(masker = Maskers.Wellformed.class) String reference,
                                @NoMask(justification = "ISO country code, not identifying") String country,
                                List<Account> accounts) {}

                        public static class Account {
                            @PII(category = PiiCategory.ACCOUNT_NUMBER)
                            private String number;

                            public String getNumber() {
                                return number;
                            }
                        }
                    }
                    """);

            assertThat(result.all()).isEmpty();
        }
    }
}
