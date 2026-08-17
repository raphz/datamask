package ch.raph.datamask.spring;

import ch.raph.datamask.application.DataMask;
import ch.raph.datamask.jdbc.MaskingDataSource;
import ch.raph.datamask.jdbc.SqlExceptionSanitizer;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Wraps every {@link DataSource} bean so a database error cannot carry a row value into a log.
 *
 * <p>This is the integration that pays for itself soonest. A unique-constraint violation from
 * PostgreSQL quotes the offending row in its detail field verbatim — {@code Key (email)=(...)
 * already exists} — and that message travels through the exception, the stack trace and every log
 * shipper downstream without anything in the application ever deciding to log an email address.
 *
 * <p>Wrapping happens in a {@link org.springframework.beans.factory.config.BeanPostProcessor}
 * rather than by replacing a specific auto-configured bean, so it covers a pool Boot configured, a
 * pool the application declared, and each member of a multi-tenant set of them, without this module
 * knowing which of Boot's JDBC auto-configurations produced it.
 *
 * <p>{@code unwrap} still returns the driver's own objects, so a pool's metrics, a health indicator
 * and code reaching for {@code PGConnection} all keep working. What does not survive is an
 * injection point declared as the pool's own type — an {@code @Autowired HikariDataSource} — since
 * the bean is a {@code MaskingDataSource} once wrapped. {@code datamask.jdbc.excluded-beans} is the
 * escape hatch for the case where that cannot be changed; see
 * {@link MaskingDataSourceBeanPostProcessor}.
 */
@AutoConfiguration(after = DataMaskAutoConfiguration.class)
@ConditionalOnClass({MaskingDataSource.class, DataSource.class})
@ConditionalOnBean(DataMask.class)
@ConditionalOnBooleanProperty(name = "datamask.jdbc.enabled", matchIfMissing = true)
public final class DataMaskJdbcAutoConfiguration {

    /**
     * Static, and given providers rather than the beans, because a {@code BeanPostProcessor} is
     * instantiated before most of the container exists. Resolving the {@code DataMask} eagerly here
     * would drag it and everything it depends on out of the ordinary lifecycle.
     */
    @Bean
    static MaskingDataSourceBeanPostProcessor maskingDataSourceBeanPostProcessor(
            ObjectProvider<DataMask> dataMask, ObjectProvider<DataMaskProperties> properties) {
        return new MaskingDataSourceBeanPostProcessor(dataMask, properties);
    }

    /**
     * The same sanitisation, for an exception that reached the application rather than the driver —
     * a {@code @ControllerAdvice} deciding what to put in a problem detail, say.
     */
    @Bean
    @ConditionalOnMissingBean
    SqlExceptionSanitizer sqlExceptionSanitizer(DataMask dataMask) {
        return new SqlExceptionSanitizer(dataMask);
    }
}
