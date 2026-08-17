package ch.raph.datamask.log4j2;

import ch.raph.datamask.application.DataMask;
import ch.raph.datamask.application.MaskingEngine;
import ch.raph.datamask.application.ResolvedMasker;
import org.apache.logging.log4j.core.Core;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.rewrite.RewritePolicy;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;

/**
 * Masks every event a {@code Rewrite} appender forwards.
 *
 * <pre>{@code
 * <Rewrite name="MASKED">
 *     <AppenderRef ref="CONSOLE"/>
 *     <AppenderRef ref="FILE"/>
 *     <DataMask secret="${env:DATAMASK_SECRET}"/>
 * </Rewrite>
 *
 * <Root level="INFO">
 *     <AppenderRef ref="MASKED"/>
 * </Root>
 * }</pre>
 *
 * <p>Rewriting the event rather than the rendered text is what makes this complete. Everything
 * downstream — every appender, layout and log shipper — sees only the masked event, so a JSON layout
 * writing the exception and the thread context map is covered as well as a pattern, and the appenders
 * that were already configured stay exactly as they are. {@link MaskingMessagePatternConverter} is the
 * layout-level alternative, and it reaches the message alone.
 *
 * <h2>Where the DataMask comes from</h2>
 *
 * The {@code secret} attribute, then {@link DataMaskLog4j2#install}, then a fallback of strict masking
 * under an ephemeral key that reports an error to the status logger. Prefer a lookup —
 * {@code ${env:DATAMASK_SECRET}} — over the value itself: a log4j2 configuration is in version control.
 *
 * <h2>What it costs</h2>
 *
 * An event carrying no PII is forwarded as the same object, so the only cost on a clean line is the
 * scan itself. See {@link LogEventMasker} for what is examined.
 */
@Plugin(name = "DataMask", category = Core.CATEGORY_NAME, elementType = "rewritePolicy", printObject = true)
public final class MaskingRewritePolicy implements RewritePolicy {

    private final ResolvedMasker<LogEventMasker> maskers;

    private MaskingRewritePolicy(ResolvedMasker<LogEventMasker> maskers) {
        this.maskers = maskers;
    }

    /** For an application that builds its own engine — a Spring auto-configuration, or a test. */
    public MaskingRewritePolicy(DataMask dataMask) {
        this(LogEventMaskers.of(dataMask));
    }

    public MaskingRewritePolicy(MaskingEngine engine) {
        this(LogEventMaskers.of(engine));
    }

    @PluginFactory
    public static MaskingRewritePolicy createPolicy(
            @PluginAttribute(value = "secret", sensitive = true) String secret) {
        return new MaskingRewritePolicy(LogEventMaskers.forSecret(secret));
    }

    @Override
    public LogEvent rewrite(LogEvent source) {
        return maskers.get().mask(source);
    }

    @Override
    public String toString() {
        // printObject is on, so this is written to the status log. It names no secret.
        return "DataMask";
    }
}
