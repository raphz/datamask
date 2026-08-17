package ch.raph.datamask.spring;

import static org.assertj.core.api.Assertions.assertThat;

import ch.raph.datamask.application.DataMask;
import ch.raph.datamask.jackson.DataMaskModule;
import ch.raph.datamask.jdbc.MaskingDataSource;
import ch.raph.datamask.jdbc.SqlExceptionSanitizer;
import ch.raph.datamask.kafka.DataMaskKafka;
import ch.raph.datamask.log4j2.DataMaskLog4j2;
import ch.raph.datamask.logback.DataMaskLogback;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@DisplayName("Each integration is wired up when its module is on the classpath, and left alone otherwise")
class IntegrationAutoConfigurationTest {

    private static final String SECRET = "test-secret-0123456789";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    DataMaskAutoConfiguration.class,
                    DataMaskJacksonAutoConfiguration.class,
                    DataMaskJdbcAutoConfiguration.class,
                    DataMaskKafkaAutoConfiguration.class,
                    DataMaskLog4j2AutoConfiguration.class,
                    DataMaskLogbackAutoConfiguration.class))
            .withPropertyValues("datamask.secret=" + SECRET);

    @AfterEach
    void forgetAnythingLeftInstalled() {
        DataMaskLogback.uninstall();
        DataMaskLog4j2.uninstall();
        DataMaskKafka.uninstall();
    }

    @Nested
    @DisplayName("Jackson")
    class Jackson {

        @Test
        @DisplayName("declares the module as a bean, which is all Boot's Jackson auto-configuration needs to "
                + "register it on every mapper it builds")
        void registersTheModule() {
            runner.run(context -> assertThat(context).hasSingleBean(DataMaskModule.class));
        }

        @Test
        @DisplayName("stays out of the way when the application declared a module of its own")
        void backsOffForAnApplicationModule() {
            DataMask own = DataMask.builder().secret(SECRET).build();
            runner.withBean(DataMaskModule.class, () -> new DataMaskModule(own))
                    .run(context -> assertThat(context).hasSingleBean(DataMaskModule.class));
        }

        @Test
        @DisplayName("does nothing when datamask-jackson is not on the classpath")
        void absentWithoutTheModule() {
            runner.withClassLoader(new FilteredClassLoader(DataMaskModule.class))
                    .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(DataMaskModule.class));
        }

        @Test
        @DisplayName("can be switched off on its own, leaving the rest of the wiring in place")
        void disabled() {
            runner.withPropertyValues("datamask.jackson.enabled=false").run(context -> {
                assertThat(context).doesNotHaveBean(DataMaskModule.class);
                assertThat(context).hasSingleBean(DataMask.class);
            });
        }
    }

    @Nested
    @DisplayName("The logging modules")
    class Logging {

        @Test
        @DisplayName("installs the context's DataMask where an appender configured in XML will find it, "
                + "because logging starts long before there is a bean to hand it")
        void installsIntoBothBackends() {
            runner.run(context -> {
                DataMask dataMask = context.getBean(DataMask.class);
                assertThat(DataMaskLogback.installed()).contains(dataMask);
                assertThat(DataMaskLog4j2.installed()).contains(dataMask);
            });
        }

        @Test
        @DisplayName("forgets the instance again when the context closes, so a test class running many "
                + "contexts does not leave a dead one behind")
        void uninstallsOnClose() {
            runner.run(context -> {});
            assertThat(DataMaskLogback.installed()).isEmpty();
            assertThat(DataMaskLog4j2.installed()).isEmpty();
        }

        @Test
        @DisplayName("leaves an instance another context installed alone, rather than stripping masking out "
                + "from under it")
        void doesNotUninstallSomebodyElsesInstance() {
            DataMask other = DataMask.builder().secret(SECRET).build();
            runner.run(context -> DataMaskLogback.install(other));
            assertThat(DataMaskLogback.installed()).contains(other);
        }

        @Test
        @DisplayName("can be switched off per backend")
        void disabled() {
            runner.withPropertyValues("datamask.logback.enabled=false", "datamask.log4j2.enabled=false")
                    .run(context -> {
                        assertThat(DataMaskLogback.installed()).isEmpty();
                        assertThat(DataMaskLog4j2.installed()).isEmpty();
                    });
        }

        @Test
        @DisplayName("does nothing when neither logging module is on the classpath")
        void absentWithoutTheModules() {
            runner.withClassLoader(new FilteredClassLoader(DataMaskLogback.class, DataMaskLog4j2.class))
                    .run(context -> assertThat(context).hasNotFailed().hasSingleBean(DataMask.class));
        }
    }

    @Nested
    @DisplayName("Kafka")
    class Kafka {

        @Test
        @DisplayName("installs the context's DataMask, because a producer builds its serializers from class "
                + "names and has nothing to pass an engine into")
        void installs() {
            runner.run(context -> assertThat(DataMaskKafka.installed()).contains(context.getBean(DataMask.class)));
        }

        @Test
        @DisplayName("can be switched off on its own")
        void disabled() {
            runner.withPropertyValues("datamask.kafka.enabled=false")
                    .run(context -> assertThat(DataMaskKafka.installed()).isEmpty());
        }
    }

    @Nested
    @DisplayName("JDBC")
    class Jdbc {

        private final ApplicationContextRunner withDataSource = runner.withUserConfiguration(StubDataSource.class);

        @Test
        @DisplayName("wraps the DataSource bean, so a unique-constraint violation cannot carry the row it "
                + "collided with into a log")
        void wrapsTheDataSource() {
            withDataSource.run(
                    context -> assertThat(context.getBean(DataSource.class)).isInstanceOf(MaskingDataSource.class));
        }

        @Test
        @DisplayName("keeps the original reachable, so a pool's own configuration and metrics still work")
        void keepsTheDelegate() {
            withDataSource.run(context -> {
                MaskingDataSource masked = (MaskingDataSource) context.getBean(DataSource.class);
                assertThat(masked.delegate()).isInstanceOf(NoConnectionDataSource.class);
            });
        }

        @Test
        @DisplayName("does not wrap twice when the application already wrapped it itself")
        void doesNotWrapAMaskingDataSource() {
            runner.withUserConfiguration(AlreadyMasked.class).run(context -> {
                MaskingDataSource masked = (MaskingDataSource) context.getBean(DataSource.class);
                assertThat(masked.delegate()).isInstanceOf(NoConnectionDataSource.class);
            });
        }

        @Test
        @DisplayName("wraps last, so a pool decorator or a tracing proxy has already had its say and the "
                + "masking ends up outermost rather than underneath something free to re-expose the driver")
        void runsLast() {
            withDataSource.run(context -> {
                Object processor = context.getBean(MaskingDataSourceBeanPostProcessor.class);
                assertThat(processor).isInstanceOf(Ordered.class);
                assertThat(((Ordered) processor).getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
            });
        }

        @Test
        @DisplayName("sits outside a post-processor that decorated the pool first, so an error from the "
                + "decorated pool still passes through the masking on its way out")
        void wrapsOutsideAnEarlierDecorator() {
            withDataSource.withUserConfiguration(PoolDecorator.class).run(context -> {
                MaskingDataSource masked = (MaskingDataSource) context.getBean(DataSource.class);
                assertThat(masked.delegate()).isInstanceOf(DecoratedDataSource.class);
            });
        }

        @Test
        @DisplayName("leaves a named DataSource bean unwrapped, which is the escape hatch for the injection "
                + "point that wants the pool's own type and cannot be changed")
        void excludedBeanIsLeftAlone() {
            runner.withUserConfiguration(TwoDataSources.class)
                    .withPropertyValues("datamask.jdbc.excluded-beans=reportingDataSource")
                    .run(context -> {
                        assertThat(context.getBean("primaryDataSource")).isInstanceOf(MaskingDataSource.class);
                        assertThat(context.getBean("reportingDataSource")).isNotInstanceOf(MaskingDataSource.class);
                    });
        }

        @Test
        @DisplayName(
                "excludes nothing by default, so forgetting the property cannot be what leaves a pool " + "unmasked")
        void nothingIsExcludedByDefault() {
            runner.withUserConfiguration(TwoDataSources.class).run(context -> {
                assertThat(context.getBean("primaryDataSource")).isInstanceOf(MaskingDataSource.class);
                assertThat(context.getBean("reportingDataSource")).isInstanceOf(MaskingDataSource.class);
            });
        }

        @Test
        @DisplayName("offers the sanitizer on its own, for an exception that reached a @ControllerAdvice "
                + "rather than the driver")
        void exposesTheSanitizer() {
            withDataSource.run(context -> assertThat(context).hasSingleBean(SqlExceptionSanitizer.class));
        }

        @Test
        @DisplayName("leaves the DataSource untouched when switched off")
        void disabled() {
            withDataSource.withPropertyValues("datamask.jdbc.enabled=false").run(context -> {
                assertThat(context.getBean(DataSource.class)).isNotInstanceOf(MaskingDataSource.class);
                assertThat(context).doesNotHaveBean(SqlExceptionSanitizer.class);
            });
        }

        @Test
        @DisplayName("does nothing when datamask-jdbc is not on the classpath")
        void absentWithoutTheModule() {
            withDataSource
                    .withClassLoader(new FilteredClassLoader(MaskingDataSource.class))
                    .run(context ->
                            assertThat(context.getBean(DataSource.class)).isInstanceOf(NoConnectionDataSource.class));
        }
    }

    // --- fixtures ------------------------------------------------------------------------------

    @Configuration(proxyBeanMethods = false)
    static class StubDataSource {

        @Bean
        DataSource dataSource() {
            return new NoConnectionDataSource();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TwoDataSources {

        @Bean
        DataSource primaryDataSource() {
            return new NoConnectionDataSource();
        }

        @Bean
        DataSource reportingDataSource() {
            return new NoConnectionDataSource();
        }
    }

    /**
     * Stands in for a pool decorator or a tracing proxy: another post-processor that wraps
     * DataSources. Declared by its concrete type on purpose — Spring buckets post-processors by the
     * type the bean definition advertises, so a factory method returning {@code BeanPostProcessor}
     * would hide the {@code Ordered} and land in the unordered group.
     */
    @Configuration(proxyBeanMethods = false)
    static class PoolDecorator {

        @Bean
        static OrderedDecorator decorator() {
            return new OrderedDecorator();
        }
    }

    static final class OrderedDecorator implements BeanPostProcessor, Ordered {

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            return bean instanceof NoConnectionDataSource pool ? new DecoratedDataSource(pool) : bean;
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }
    }

    static final class DecoratedDataSource extends NoConnectionDataSource {

        private final DataSource delegate;

        DecoratedDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        DataSource delegate() {
            return delegate;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class AlreadyMasked {

        @Bean
        DataSource dataSource() {
            return new MaskingDataSource(
                    new NoConnectionDataSource(),
                    DataMask.builder().secret(SECRET).build());
        }
    }

    /** Enough of a {@code DataSource} to be a bean. Nothing here opens a connection. */
    static class NoConnectionDataSource implements DataSource {

        @Override
        public Connection getConnection() throws SQLException {
            throw new SQLException("no connection in this test");
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            throw new SQLException("no connection in this test");
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {}

        @Override
        public void setLoginTimeout(int seconds) {}

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            if (iface.isInstance(this)) {
                return iface.cast(this);
            }
            throw new SQLException("not a wrapper for " + iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return iface.isInstance(this);
        }
    }
}
