package ch.raph.datamask.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.raph.datamask.application.DataMask;
import ch.raph.datamask.domain.MaskingObserver;
import org.junit.jupiter.api.Test;

class DebugTest {

    @Test
    void printFailure() {
        DataMask dataMask = DataMask.builder()
                .secret("a-test-secret-of-sufficient-length")
                .observer(new MaskingObserver() {
                    @Override
                    public void onFailure(String path, Throwable error) {
                        System.out.println("FAILURE at " + path);
                        error.printStackTrace(System.out);
                    }
                })
                .build();
        LoggerContext context = new LoggerContext();
        LoggingEvent event =
                new LoggingEvent(DebugTest.class.getName(), context.getLogger("x"), Level.INFO, "started", null, null);
        System.out.println("RESULT: " + new LoggingEventMasker(dataMask).mask(event));
    }
}
