package ch.raph.datamask.spring;

import ch.raph.datamask.application.DataMask;
import ch.raph.datamask.logback.DataMaskLogback;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Hands the application's {@link DataMask} to Logback.
 *
 * <p>The appender cannot be given anything when it is built: {@code logback.xml} is read long
 * before the application has a container or a bean. So the module keeps a static hand-off that a
 * {@code MaskingAppender} consults per event, and this is what fills it in — an appender that
 * started under an ephemeral key picks the real one up from the next log line onwards.
 *
 * <p>What this does <em>not</em> do is attach a {@code MaskingAppender} for you. Where masking sits
 * in an appender chain is a decision about that chain — which appenders it covers, and whether an
 * async appender is inside it or outside — and rewriting somebody's logging pipeline from a library
 * on the classpath is not a decision to make on their behalf. Five lines in {@code logback.xml}
 * declare it; see {@code datamask-logback}.
 */
@AutoConfiguration(after = DataMaskAutoConfiguration.class)
@ConditionalOnClass(DataMaskLogback.class)
@ConditionalOnBean(DataMask.class)
@ConditionalOnBooleanProperty(name = "datamask.logback.enabled", matchIfMissing = true)
public final class DataMaskLogbackAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    LogbackDataMaskInstaller logbackDataMaskInstaller(DataMask dataMask) {
        return new LogbackDataMaskInstaller(dataMask);
    }
}
