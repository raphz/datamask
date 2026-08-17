package ch.raph.datamask.logback;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import ch.qos.logback.core.spi.AppenderAttachable;
import ch.qos.logback.core.spi.AppenderAttachableImpl;
import ch.raph.datamask.application.DataMask;
import ch.raph.datamask.application.MaskingEngine;
import ch.raph.datamask.application.ResolvedMasker;
import java.util.Iterator;
import java.util.Objects;

/**
 * Masks every event on its way to the appenders underneath it.
 *
 * <pre>{@code
 * <appender name="MASKED" class="ch.raph.datamask.logback.MaskingAppender">
 *     <secret>${DATAMASK_SECRET}</secret>
 *     <appender-ref ref="CONSOLE"/>
 *     <appender-ref ref="FILE"/>
 * </appender>
 *
 * <root level="INFO">
 *     <appender-ref ref="MASKED"/>
 * </root>
 * }</pre>
 *
 * <p>Wrapping rather than converting is what makes this complete. A masking pattern converter would
 * cover the message and nothing else: the MDC would need its own, the stack trace another, and a JSON
 * encoder would bypass all of them and write the event's own fields. Here the event itself is replaced,
 * so everything downstream — every appender, encoder, layout and log shipper — sees only the masked
 * one, and appenders that were already configured stay exactly as they are.
 *
 * <h2>Where the DataMask comes from</h2>
 *
 * In order: what was configured on this appender — a {@code <secret>}, or a {@code DataMask} set on it
 * directly — then {@link DataMaskLogback#install}, then a fallback of strict masking under an ephemeral
 * key. The fallback is loud, reporting an error to logback's status manager, and it is safe: everything
 * is masked, but a {@code HASH} pseudonym differs after a restart, which removes the reason to prefer it
 * over {@code REDACT}.
 *
 * <p>The installed instance is looked up per event rather than at startup, because a {@code DataMask}
 * bean cannot exist while {@code logback.xml} is being read. One installed after logging has begun is
 * picked up from the next event.
 *
 * <h2>What it costs</h2>
 *
 * An event carrying no PII is forwarded as the same object, so the only cost on a clean line is the
 * scan itself. See {@link LoggingEventMasker} for what is examined.
 */
public final class MaskingAppender extends UnsynchronizedAppenderBase<ILoggingEvent>
        implements AppenderAttachable<ILoggingEvent> {

    private final AppenderAttachableImpl<ILoggingEvent> nested = new AppenderAttachableImpl<>();

    /** Configured on this appender, and then the only thing consulted. Null until something is. */
    private volatile ResolvedMasker<LoggingEventMasker> configured;

    /**
     * The installed-or-fallback path. Keyed on the installed instance by {@link ResolvedMasker}, so a
     * later {@code install} is not missed and the fallback is built once rather than per event.
     */
    private final ResolvedMasker<LoggingEventMasker> resolved =
            ResolvedMasker.installed(DataMaskLogback.holder(), LoggingEventMasker::new, this::ephemeralFallback);

    private String secret;

    /** For an application that builds its own engine — a Spring auto-configuration, or a test. */
    public void setDataMask(DataMask dataMask) {
        this.configured = ResolvedMasker.of(new LoggingEventMasker(Objects.requireNonNull(dataMask, "dataMask")));
    }

    public void setEngine(MaskingEngine engine) {
        this.configured = ResolvedMasker.of(new LoggingEventMasker(Objects.requireNonNull(engine, "engine")));
    }

    /**
     * The pseudonymisation secret, for a deployment configured entirely in XML. Use a substitution —
     * {@code ${DATAMASK_SECRET}} — rather than the value itself: this file is in version control.
     */
    public void setSecret(String secret) {
        this.secret = secret;
    }

    @Override
    protected void append(ILoggingEvent event) {
        nested.appendLoopOnAppenders(masker().mask(event));
    }

    @Override
    public void start() {
        if (!nested.iteratorForAppenders().hasNext()) {
            // Starting with nothing attached would discard every event that reaches here. Refusing
            // is louder, and matches what AsyncAppender does in the same situation.
            addError("No appender-ref attached to masking appender [" + getName() + "]; nothing to forward to.");
            return;
        }
        if (configured == null && secret != null && !secret.isBlank()) {
            configured = ResolvedMasker.of(new LoggingEventMasker(fromSecret()));
        }
        super.start();
    }

    @Override
    public void stop() {
        if (!isStarted()) {
            return;
        }
        super.stop();
        nested.detachAndStopAllAppenders();
    }

    private LoggingEventMasker masker() {
        ResolvedMasker<LoggingEventMasker> own = configured;
        return (own != null ? own : resolved).get();
    }

    /** Called once per distinct installed state, never per event — see {@link ResolvedMasker}. */
    private DataMask ephemeralFallback() {
        addError("No secret and no DataMask configured on masking appender [" + getName()
                + "]; masking with strict defaults and an ephemeral key, so pseudonyms will not be comparable"
                + " across restarts. Set <secret> or call DataMaskLogback.install(...).");
        return DataMask.withDefaults();
    }

    private DataMask fromSecret() {
        try {
            return DataMask.builder().secret(secret).build();
        } catch (RuntimeException rejected) {
            // A secret too short to pseudonymise with. Every value is still masked under an ephemeral
            // key, so this is loud rather than fatal: dropping every log line would be the larger
            // outage. The message names no part of the secret.
            addError(
                    "The secret configured on masking appender [" + getName()
                            + "] was rejected; masking with an ephemeral key instead.",
                    rejected);
            return DataMask.withDefaults();
        }
    }

    @Override
    public void addAppender(Appender<ILoggingEvent> newAppender) {
        nested.addAppender(newAppender);
    }

    @Override
    public Iterator<Appender<ILoggingEvent>> iteratorForAppenders() {
        return nested.iteratorForAppenders();
    }

    @Override
    public Appender<ILoggingEvent> getAppender(String name) {
        return nested.getAppender(name);
    }

    @Override
    public boolean isAttached(Appender<ILoggingEvent> appender) {
        return nested.isAttached(appender);
    }

    @Override
    public void detachAndStopAllAppenders() {
        nested.detachAndStopAllAppenders();
    }

    @Override
    public boolean detachAppender(Appender<ILoggingEvent> appender) {
        return nested.detachAppender(appender);
    }

    @Override
    public boolean detachAppender(String name) {
        return nested.detachAppender(name);
    }
}
