package ch.raph.datamask.spring;

import ch.raph.datamask.application.DataMask;
import ch.raph.datamask.jdbc.MaskingDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Puts a {@link MaskingDataSource} in front of every {@link DataSource} bean.
 *
 * <p>Post-processing rather than bean replacement is what makes this complete: it catches the pool
 * Boot configured and the one the application declared alike, and it does not care which of Boot's
 * JDBC auto-configurations, or none of them, produced the bean.
 *
 * <p>Wrapping after initialisation leaves the pool fully configured first, and an already-wrapped
 * bean is left alone so a second context refresh cannot stack proxies.
 */
final class MaskingDataSourceBeanPostProcessor implements BeanPostProcessor {

    private final ObjectProvider<DataMask> dataMask;

    MaskingDataSourceBeanPostProcessor(ObjectProvider<DataMask> dataMask) {
        this.dataMask = dataMask;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof MaskingDataSource || !(bean instanceof DataSource dataSource)) {
            return bean;
        }
        return new MaskingDataSource(dataSource, dataMask.getObject());
    }
}
