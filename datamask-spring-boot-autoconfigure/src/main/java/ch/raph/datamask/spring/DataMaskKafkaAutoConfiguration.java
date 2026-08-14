package ch.raph.datamask.spring;

import ch.raph.datamask.application.DataMask;
import ch.raph.datamask.kafka.DataMaskKafka;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Hands the application's {@link DataMask} to the Kafka plugins.
 *
 * <p>A producer builds its serializers and interceptors from class <em>names</em>, through a
 * no-argument constructor and a map of strings, so there is nothing to pass an engine into. The
 * module keeps a static hand-off for that reason and this fills it in — which covers the
 * configuration Boot itself produces, where {@code spring.kafka.producer.value-serializer} and
 * {@code spring.kafka.producer.properties.interceptor.classes} are exactly such names.
 *
 * <p>An application that hands {@code DefaultKafkaProducerFactory} serializer instances it built
 * itself should use the module's constructors instead; the install is then redundant rather than
 * wrong.
 */
@AutoConfiguration(after = DataMaskAutoConfiguration.class)
@ConditionalOnClass(DataMaskKafka.class)
@ConditionalOnBean(DataMask.class)
@ConditionalOnBooleanProperty(name = "datamask.kafka.enabled", matchIfMissing = true)
public final class DataMaskKafkaAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    KafkaDataMaskInstaller kafkaDataMaskInstaller(DataMask dataMask) {
        return new KafkaDataMaskInstaller(dataMask);
    }
}
