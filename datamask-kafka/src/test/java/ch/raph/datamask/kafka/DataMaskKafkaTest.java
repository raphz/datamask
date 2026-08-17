package ch.raph.datamask.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import ch.raph.datamask.api.MaskStrategy;
import ch.raph.datamask.api.PiiCategory;
import ch.raph.datamask.application.DataMask;
import ch.raph.datamask.application.ResolvedMasker;
import ch.raph.datamask.domain.MaskingObserver;
import ch.raph.datamask.kafka.testdomain.Payments;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The hand-off itself, rather than any one plugin's use of it. What the plugins share is
 * {@code DataMaskKafka.resolving(...)}, so this is where the resolution rules are pinned: a late
 * install takes effect, the fallback is built once rather than per record, and a masker the
 * application handed to a constructor is never displaced by one installed afterwards.
 */
@DisplayName("Where a plugin Kafka built from a class name finds its DataMask")
class DataMaskKafkaTest {

    private static final String IBAN = "CH9300762011623852957";

    @AfterEach
    void forgetTheInstalledInstance() {
        DataMaskKafka.uninstall();
    }

    @Test
    @DisplayName("reports what was installed, which is what the Spring auto-configuration checks before it "
            + "uninstalls again")
    void reportsWhatWasInstalled() {
        DataMask dataMask = DataMask.withDefaults();

        assertThat(DataMaskKafka.installed()).isEmpty();
        DataMaskKafka.install(dataMask);
        assertThat(DataMaskKafka.installed()).contains(dataMask);

        DataMaskKafka.uninstall();
        assertThat(DataMaskKafka.installed()).isEmpty();
    }

    @Test
    @DisplayName("builds the fallback once rather than per record, because it is keyed on the installed instance "
            + "and that stays null while nothing is installed")
    void buildsTheFallbackOnce() {
        ResolvedMasker<RecordMasker> source = DataMaskKafka.resolving(false, Set.of());

        assertThat(source.get()).isSameAs(source.get()).isSameAs(source.get());
    }

    @Test
    @DisplayName("picks up a DataMask installed after the plugin was built, from the next record on")
    void picksUpALateInstall() {
        ResolvedMasker<RecordMasker> source = DataMaskKafka.resolving(false, Set.of());
        RecordMasker fallback = source.get();

        // An observer of its own, so what is asserted is that the masker was rebuilt against this
        // instance rather than merely rebuilt.
        MaskingObserver mine = new MaskingObserver() {};
        DataMask installed = DataMask.builder().observer(mine).build();
        DataMaskKafka.install(installed);

        RecordMasker afterInstall = source.get();
        assertThat(afterInstall).isNotSameAs(fallback);
        assertThat(afterInstall.observer()).isSameAs(mine);
        // And then cached against it, so the cost from here is a volatile read.
        assertThat(source.get()).isSameAs(afterInstall);
    }

    @Test
    @DisplayName("goes back to the fallback when the installed instance is taken away, rather than holding a "
            + "DataMask a container has already shut down")
    void rewiresBackAfterAnUninstall() {
        ResolvedMasker<RecordMasker> source = DataMaskKafka.resolving(false, Set.of());
        DataMask installed = DataMask.withDefaults();
        DataMaskKafka.install(installed);
        RecordMasker whileInstalled = source.get();

        DataMaskKafka.uninstall();

        assertThat(source.get()).isNotSameAs(whileInstalled);
    }

    @Test
    @DisplayName("carries the settings the plugin read out of its client's configuration into the masker it builds "
            + "for whatever is installed")
    void carriesTheConfiguredSettingsIntoTheMasker() {
        DataMaskKafka.install(DataMask.withDefaults());
        RecordMasker masker =
                DataMaskKafka.resolving(true, Set.of("x-customer-ref")).get();

        ProducerRecord<String, String> masked = masker.mask(new ProducerRecord<>("payments", "pay to " + IBAN, "ok"));

        assertThat(masked.key()).doesNotContain(IBAN);
    }

    @Test
    @DisplayName("leaves a plugin that was handed its own masker alone, so an install elsewhere in the application "
            + "never displaces what the constructor was given")
    void aConfiguredMaskerWinsOverAnInstalledOne() {
        List<String> installedSaw = new ArrayList<>();
        DataMaskKafka.install(DataMask.builder()
                .observer(new MaskingObserver() {
                    @Override
                    public void onMasked(String path, PiiCategory category, MaskStrategy strategy) {
                        installedSaw.add(path);
                    }
                })
                .build());

        List<String> configuredSaw = new ArrayList<>();
        DataMask configured = DataMask.builder()
                .observer(new MaskingObserver() {
                    @Override
                    public void onMasked(String path, PiiCategory category, MaskStrategy strategy) {
                        configuredSaw.add(path);
                    }
                })
                .build();
        ProducerInterceptor<String, Payments.Payment> interceptor = new MaskingProducerInterceptor<>(configured);

        interceptor.onSend(new ProducerRecord<>("payments", "cust-4711", payment()));

        assertThat(configuredSaw).isNotEmpty();
        assertThat(installedSaw).isEmpty();
    }

    @Test
    @DisplayName("still resolves for a plugin Kafka configured before anything was installed, under strict defaults "
            + "and an ephemeral key rather than not at all")
    void resolvesWithNothingInstalled() {
        ProducerInterceptor<String, Payments.Payment> interceptor = new MaskingProducerInterceptor<>();
        interceptor.configure(Map.of());

        assertThat(interceptor
                        .onSend(new ProducerRecord<>("payments", "cust-4711", payment()))
                        .value()
                        .iban())
                .doesNotContain(IBAN);
    }

    private static Payments.Payment payment() {
        return new Payments.Payment(new Payments.Email("john@example.com"), IBAN, "4111111111111111", "CHF", 4_250L);
    }
}
