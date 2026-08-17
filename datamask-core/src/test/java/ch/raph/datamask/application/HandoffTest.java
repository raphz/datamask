package ch.raph.datamask.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The static hand-off three integrations rely on, and the caching rule that made it worth sharing.
 *
 * <p>A logback appender, a log4j2 plugin and a Kafka interceptor are all built before an application
 * has a container, so none of them can be handed a {@code DataMask}. Each looked one up per event
 * instead, and each wrote the same cache to avoid paying for it — keyed on the identity of the
 * installed instance, which stays null while nothing is installed. Get that key wrong and you either
 * construct a {@code DataMask} on the logging hot path, or you never notice a late install.
 */
@DisplayName("The static hand-off")
class HandoffTest {

    private static final String SECRET = "a-test-secret-of-sufficient-length";

    private final InstalledDataMask holder = InstalledDataMask.holder();

    private static DataMask instance() {
        return DataMask.builder().secret(SECRET).build();
    }

    @Nested
    @DisplayName("The holder")
    class Holder {

        @Test
        @DisplayName("hands back what was installed, and nothing before that")
        void installAndRead() {
            assertThat(holder.installed()).isEmpty();
            assertThat(holder.current()).isNull();

            DataMask dataMask = instance();
            holder.install(dataMask);

            assertThat(holder.installed()).containsSame(dataMask);
            assertThat(holder.current()).isSameAs(dataMask);
        }

        @Test
        @DisplayName("forgets on uninstall, which is what a test and a shutting-down container both need")
        void uninstall() {
            holder.install(instance());
            holder.uninstall();

            assertThat(holder.installed()).isEmpty();
            assertThat(holder.current()).isNull();
        }

        @Test
        @DisplayName("refuses null rather than quietly uninstalling")
        void rejectsNull() {
            assertThatNullPointerException().isThrownBy(() -> holder.install(null));
        }

        @Test
        @DisplayName("is per holder, so two integrations installing do not overwrite each other")
        void holdersAreIndependent() {
            InstalledDataMask other = InstalledDataMask.holder();
            holder.install(instance());

            assertThat(other.installed()).isEmpty();
        }
    }

    @Nested
    @DisplayName("The resolver")
    class Resolver {

        private final AtomicInteger built = new AtomicInteger();
        private final AtomicInteger fallbacks = new AtomicInteger();

        private ResolvedMasker<String> resolving() {
            return ResolvedMasker.installed(holder, dataMask -> "masker-" + built.incrementAndGet(), () -> {
                fallbacks.incrementAndGet();
                return DataMask.withDefaults();
            });
        }

        @Test
        @DisplayName("uses a configured masker and never looks anything up")
        void configuredWins() {
            ResolvedMasker<String> resolved = ResolvedMasker.of("its-own");
            holder.install(instance());

            assertThat(resolved.get()).isEqualTo("its-own");
            assertThat(built).hasValue(0);
        }

        @Test
        @DisplayName("builds the fallback once while nothing is installed, not once per event")
        void fallbackIsBuiltOnce() {
            ResolvedMasker<String> resolved = resolving();

            String first = resolved.get();
            String second = resolved.get();
            String third = resolved.get();

            assertThat(first).isEqualTo(second).isEqualTo(third);
            assertThat(built).hasValue(1);
            assertThat(fallbacks).hasValue(1);
        }

        @Test
        @DisplayName("picks up an install that arrives after the first event, which is the usual startup order")
        void lateInstallIsPickedUp() {
            ResolvedMasker<String> resolved = resolving();
            String beforeInstall = resolved.get();

            holder.install(instance());
            String afterInstall = resolved.get();

            assertThat(afterInstall).isNotEqualTo(beforeInstall);
            assertThat(built).hasValue(2);
        }

        @Test
        @DisplayName("then stops rebuilding, because the installed instance is the cache key")
        void rebuildsOnlyOnChange() {
            ResolvedMasker<String> resolved = resolving();
            holder.install(instance());

            resolved.get();
            resolved.get();
            resolved.get();

            assertThat(built).hasValue(1);
            assertThat(fallbacks).hasValue(0);
        }

        @Test
        @DisplayName("goes back to the fallback when the instance is uninstalled")
        void uninstallReturnsToFallback() {
            ResolvedMasker<String> resolved = resolving();
            holder.install(instance());
            resolved.get();

            holder.uninstall();
            resolved.get();

            assertThat(fallbacks).hasValue(1);
            assertThat(built).hasValue(2);
        }
    }
}
