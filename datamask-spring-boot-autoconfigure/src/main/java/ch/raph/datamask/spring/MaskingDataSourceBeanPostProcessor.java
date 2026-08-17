package ch.raph.datamask.spring;

import ch.raph.datamask.application.DataMask;
import ch.raph.datamask.jdbc.MaskingDataSource;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;

/**
 * Puts a {@link MaskingDataSource} in front of every {@link DataSource} bean.
 *
 * <p>Post-processing rather than bean replacement is what makes this complete: it catches the pool
 * Boot configured and the one the application declared alike, and it does not care which of Boot's
 * JDBC auto-configurations, or none of them, produced the bean.
 *
 * <p>Wrapping after initialisation leaves the pool fully configured first, and an already-wrapped
 * bean is left alone so a second context refresh cannot stack proxies.
 *
 * <h2>Why it runs last</h2>
 *
 * {@link Ordered#LOWEST_PRECEDENCE}, so every other ordered {@code DataSource} post-processor — a
 * pool decorator, a tracing or statement-logging proxy — has already had its say and this wrapper
 * ends up outermost. That is the position masking wants: an error surfacing from anything
 * underneath still passes through the sanitiser on its way out, whereas wrapping first would leave
 * whatever wrapped this one free to re-expose the driver's message. A post-processor that declares
 * no order at all still runs after this one; that is Spring's own registration contract — ordered
 * processors before unordered ones — and not something a bean can opt out of.
 *
 * <h2>The injection point this breaks</h2>
 *
 * The bean is a {@code MaskingDataSource} once wrapped, so an {@code @Autowired HikariDataSource} —
 * or any injection point declared as the pool's own type — no longer resolves. Injecting
 * {@code DataSource} and calling {@code unwrap(HikariDataSource.class)} keeps the masking and still
 * reaches the pool; Boot's own pool metadata, health indicators and metrics already do exactly
 * that, which is why they keep working. When a third party genuinely needs the bean to <em>be</em>
 * the pool, {@code datamask.jdbc.excluded-beans} names it and this processor leaves it alone — at
 * the cost of that pool's errors quoting the rows they collided with, which is why each exclusion
 * is logged.
 */
final class MaskingDataSourceBeanPostProcessor implements BeanPostProcessor, Ordered {

    private static final Logger log = LoggerFactory.getLogger(MaskingDataSourceBeanPostProcessor.class);

    private final ObjectProvider<DataMask> dataMask;
    private final ObjectProvider<DataMaskProperties> properties;

    MaskingDataSourceBeanPostProcessor(
            ObjectProvider<DataMask> dataMask, ObjectProvider<DataMaskProperties> properties) {
        this.dataMask = dataMask;
        this.properties = properties;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof MaskingDataSource || !(bean instanceof DataSource dataSource)) {
            return bean;
        }
        if (isExcluded(beanName)) {
            log.warn(
                    "DataSource bean '{}' is listed in datamask.jdbc.excluded-beans and is left unwrapped. "
                            + "A database error from it can still carry a row value into a log — PostgreSQL "
                            + "quotes the offending row in a unique-constraint violation.",
                    beanName);
            return bean;
        }
        return new MaskingDataSource(dataSource, dataMask.getObject());
    }

    /**
     * Runs last, so the masking sits outside whatever else decorated the pool. See the class
     * Javadoc for the one case this ordering cannot cover.
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    /**
     * Resolved on demand rather than injected, for the reason the {@code DataMask} is: a
     * {@code BeanPostProcessor} is built before most of the container exists, and only a
     * {@code DataSource} bean ever gets this far.
     */
    private boolean isExcluded(String beanName) {
        DataMaskProperties configured = properties.getIfAvailable();
        return configured != null && configured.jdbc().excludedBeans().contains(beanName);
    }
}
