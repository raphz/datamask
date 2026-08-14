package ch.raph.datamask.spring;

import ch.raph.datamask.application.DataMask;
import ch.raph.datamask.log4j2.DataMaskLog4j2;
import java.util.Objects;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

/**
 * Ties the static hand-off in {@code datamask-log4j2} to the lifecycle of a context. See
 * {@link LogbackDataMaskInstaller} for why the uninstall is conditional.
 */
final class Log4j2DataMaskInstaller implements InitializingBean, DisposableBean {

    private final DataMask dataMask;

    Log4j2DataMaskInstaller(DataMask dataMask) {
        this.dataMask = Objects.requireNonNull(dataMask, "dataMask");
    }

    @Override
    public void afterPropertiesSet() {
        DataMaskLog4j2.install(dataMask);
    }

    @Override
    public void destroy() {
        if (DataMaskLog4j2.installed().orElse(null) == dataMask) {
            DataMaskLog4j2.uninstall();
        }
    }
}
