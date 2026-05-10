package dev.tylercash.event.tfnsw;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TfnswConfigurationTest {
    @Test
    void disabledByDefaultWhenNoApiKey() {
        TfnswConfiguration c = new TfnswConfiguration();
        assertThat(c.isEnabled()).isFalse();
    }

    @Test
    void enabledWhenApiKeySet() {
        TfnswConfiguration c = new TfnswConfiguration();
        c.setApiKey("k");
        assertThat(c.isEnabled()).isTrue();
    }

    @Test
    void postConstructLogDoesNotThrow() {
        new TfnswConfiguration().log();
    }
}
