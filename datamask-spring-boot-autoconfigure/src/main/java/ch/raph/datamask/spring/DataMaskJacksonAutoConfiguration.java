package ch.raph.datamask.spring;

import ch.raph.datamask.application.DataMask;
import ch.raph.datamask.jackson.DataMaskModule;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Masks every JSON document the application writes.
 *
 * <p>Nothing needs to find the {@code ObjectMapper}: Boot's own Jackson auto-configuration collects
 * every {@link tools.jackson.databind.JacksonModule} bean and registers it on the mappers it
 * builds, so declaring the module is the whole integration. An application that constructs a
 * {@code JsonMapper} by hand is on its own, and that is the case the module's Javadoc covers.
 *
 * <p>Ordered before Boot's Jackson auto-configuration by name rather than by class, so this module
 * does not take a compile dependency on {@code spring-boot-jackson} to express an ordering that
 * only matters if Boot ever resolves its modules eagerly.
 */
@AutoConfiguration(
        after = DataMaskAutoConfiguration.class,
        beforeName = "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration")
@ConditionalOnClass(DataMaskModule.class)
@ConditionalOnBean(DataMask.class)
@ConditionalOnBooleanProperty(name = "datamask.jackson.enabled", matchIfMissing = true)
public final class DataMaskJacksonAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    DataMaskModule dataMaskModule(DataMask dataMask) {
        return new DataMaskModule(dataMask);
    }
}
