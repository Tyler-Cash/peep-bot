package dev.tylercash.event.global;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class EventCreationToggleTest {

    @Test
    void defaultTrue_isEnabled_returnsTrue() {
        EventCreationToggle toggle = new EventCreationToggle(true);
        assertThat(toggle.isEnabled()).isTrue();
    }

    @Test
    void defaultFalse_isEnabled_returnsFalse() {
        EventCreationToggle toggle = new EventCreationToggle(false);
        assertThat(toggle.isEnabled()).isFalse();
    }

    @Test
    void enable_flipsFalseToTrue() {
        EventCreationToggle toggle = new EventCreationToggle(false);
        toggle.enable();
        assertThat(toggle.isEnabled()).isTrue();
    }

    @Test
    void enable_isNoOpWhenAlreadyEnabled() {
        EventCreationToggle toggle = new EventCreationToggle(true);
        toggle.enable();
        assertThat(toggle.isEnabled()).isTrue();
    }

    @Test
    void disable_flipsTrueToFalse() {
        EventCreationToggle toggle = new EventCreationToggle(true);
        toggle.disable();
        assertThat(toggle.isEnabled()).isFalse();
    }

    @Test
    void disable_isNoOpWhenAlreadyDisabled() {
        EventCreationToggle toggle = new EventCreationToggle(false);
        toggle.disable();
        assertThat(toggle.isEnabled()).isFalse();
    }

    @Test
    void concurrentFlips_neitherDeadlocksNorThrows() throws Exception {
        EventCreationToggle toggle = new EventCreationToggle(true);
        int threads = 8;
        int iterations = 1000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int idx = t;
            futures.add(pool.submit(() -> {
                for (int i = 0; i < iterations; i++) {
                    if (idx % 2 == 0) {
                        toggle.enable();
                    } else {
                        toggle.disable();
                    }
                }
            }));
        }
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();
        // No assertion on final state — just confirm no exception / deadlock.
        boolean finalState = toggle.isEnabled();
        assertThat(finalState).isIn(true, false);
    }
}
