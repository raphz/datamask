package ch.raph.datamask.log4j2;

import ch.raph.datamask.application.ResolvedMasker;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.pattern.ConverterKeys;
import org.apache.logging.log4j.core.pattern.LogEventPatternConverter;
import org.apache.logging.log4j.core.pattern.PatternConverter;

/**
 * Masks the message from inside a layout, for a deployment that cannot restructure its appenders.
 *
 * <pre>{@code
 * <PatternLayout pattern="%d %-5level %logger{36} - %maskedMessage%n"/>
 * }</pre>
 *
 * <p>Swapping {@code %msg} for {@code %maskedMessage} is a one-line change to an existing layout, which
 * is the whole point of it. What it reaches is the message: the parameters are masked before formatting
 * and the format itself is scanned, exactly as {@link MaskingRewritePolicy} does.
 *
 * <p><b>It reaches nothing else.</b> {@code %X} renders the thread context map and {@code %ex} the
 * exception, each through a converter of its own, and both would still print what they were given. A
 * layout using this and printing either is masked by half. Use the rewrite policy when the events
 * themselves should be safe — which is the case whenever more than one layout, appender or shipper sees
 * them.
 *
 * <p>There is deliberately no {@code secret} option. A pattern string is the wrong place for one: it is
 * echoed into the status log and copied between configurations. Install a {@code DataMask} through
 * {@link DataMaskLog4j2} for keyed pseudonyms; without one, masking still happens under an ephemeral
 * key.
 */
@Plugin(name = "MaskingMessageConverter", category = PatternConverter.CATEGORY)
@ConverterKeys({"maskedMessage", "maskedMsg"})
public final class MaskingMessagePatternConverter extends LogEventPatternConverter {

    private final ResolvedMasker<LogEventMasker> maskers;

    private MaskingMessagePatternConverter() {
        super("MaskedMessage", "message");
        this.maskers = LogEventMaskers.forSecret(null);
    }

    /**
     * Log4j2 requires this signature. Options are accepted and ignored: the message conversion options
     * of {@code %m} — {@code nolookups}, {@code ansi} — do not apply to a value that has been masked,
     * and a secret does not belong in a pattern.
     */
    public static MaskingMessagePatternConverter newInstance(String[] options) {
        return new MaskingMessagePatternConverter();
    }

    @Override
    public void format(LogEvent event, StringBuilder toAppendTo) {
        toAppendTo.append(maskers.get().maskFormattedMessage(event));
    }
}
