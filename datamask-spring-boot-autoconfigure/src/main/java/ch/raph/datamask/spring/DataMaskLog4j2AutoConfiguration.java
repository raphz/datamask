package ch.raph.datamask.spring;

import ch.raph.datamask.application.DataMask;
import ch.raph.datamask.log4j2.DataMaskLog4j2;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Hands the application's {@link DataMask} to Log4j2.
 *
 * <p>Same hand-off, same reason as {@link DataMaskLogbackAutoConfiguration}: {@code log4j2.xml} is
 * read before there is a context, so the {@code DataMask} rewrite policy and pattern converter look
 * the instance up per event instead of being handed one. Until this runs they mask under an
 * ephemeral key; from the next event they use the configured secret.
 *
 * <p>Where masking sits in the appender chain stays a decision for {@code log4j2.xml} — a
 * {@code Rewrite} appender with a {@code DataMask} policy. See {@code datamask-log4j2}.
 */
@AutoConfiguration(after = DataMaskAutoConfiguration.class)
@ConditionalOnClass(DataMaskLog4j2.class)
@ConditionalOnBean(DataMask.class)
@ConditionalOnBooleanProperty(name = "datamask.log4j2.enabled", matchIfMissing = true)
public final class DataMaskLog4j2AutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Log4j2DataMaskInstaller log4j2DataMaskInstaller(DataMask dataMask) {
        return new Log4j2DataMaskInstaller(dataMask);
    }
}
