package ch.raph.datamask.spring;

import ch.raph.datamask.application.DataMask;
import ch.raph.datamask.kafka.DataMaskKafka;
import java.util.Objects;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

/**
 * Ties the static hand-off in {@code datamask-kafka} to the lifecycle of a context. See
 * {@link LogbackDataMaskInstaller} for why the uninstall is conditional.
 */
final class KafkaDataMaskInstaller implements InitializingBean, DisposableBean {

    private final DataMask dataMask;

    KafkaDataMaskInstaller(DataMask dataMask) {
        this.dataMask = Objects.requireNonNull(dataMask, "dataMask");
    }

    @Override
    public void afterPropertiesSet() {
        DataMaskKafka.install(dataMask);
    }

    @Override
    public void destroy() {
        if (DataMaskKafka.installed().orElse(null) == dataMask) {
            DataMaskKafka.uninstall();
        }
    }
}
