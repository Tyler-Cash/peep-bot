package dev.tylercash.event.discord;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

class AvatarDownloadServiceTest {

    @Test
    void download_returnsEmpty_whenUrlIsNull() {
        AvatarDownloadService service = new AvatarDownloadService(ObservationRegistry.NOOP);
        assertThat(service.download(null)).isEmpty();
    }

    @Test
    void download_returnsEmpty_whenUrlIsBlank() {
        AvatarDownloadService service = new AvatarDownloadService(ObservationRegistry.NOOP);
        assertThat(service.download("  ")).isEmpty();
    }
}
