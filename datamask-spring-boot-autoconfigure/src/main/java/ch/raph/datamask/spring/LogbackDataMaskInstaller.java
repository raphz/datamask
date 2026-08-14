package ch.raph.datamask.spring;

import ch.raph.datamask.application.DataMask;
import ch.raph.datamask.logback.DataMaskLogback;
import java.util.Objects;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

/**
 * Ties the static hand-off in {@code datamask-logback} to the lifecycle of a context.
 *
 * <p>Uninstalling only what this context installed matters more than it looks: a Boot test class
 * runs many contexts, and an unconditional uninstall would let one closing context strip the
 * masking out from under another that is still logging.
 */
final class LogbackDataMaskInstaller implements InitializingBean, DisposableBean {

    private final DataMask dataMask;

    LogbackDataMaskInstaller(DataMask dataMask) {
        this.dataMask = Objects.requireNonNull(dataMask, "dataMask");
    }

    @Override
    public void afterPropertiesSet() {
        DataMaskLogback.install(dataMask);
    }

    @Override
    public void destroy() {
        if (DataMaskLogback.installed().orElse(null) == dataMask) {
            DataMaskLogback.uninstall();
        }
    }
}
