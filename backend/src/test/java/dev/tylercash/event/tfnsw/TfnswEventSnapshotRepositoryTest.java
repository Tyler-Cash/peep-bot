package dev.tylercash.event.tfnsw;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.test.AbstractHttpIntegrationTest;
import dev.tylercash.event.test.TestIds;
import java.time.Instant;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TfnswEventSnapshotRepositoryTest extends AbstractHttpIntegrationTest {
    @Autowired
    TfnswEventSnapshotRepository repo;

    @Test
    void roundTripsHashAndPostedAt() {
        long guildId = TestIds.nextLong();
        Event e = fixtures.seedEvent(
                guildId, "123456789", "Test Event", ZonedDateTime.now().plusDays(1), ev -> {});
        TfnswEventSnapshot snap = new TfnswEventSnapshot();
        snap.setEventId(e.getId());
        snap.setAlertIdsHash("deadbeef");
        snap.setLastPostedAt(Instant.parse("2026-05-10T00:00:00Z"));
        repo.save(snap);

        TfnswEventSnapshot reloaded = repo.findById(e.getId()).orElseThrow();
        assertThat(reloaded.getAlertIdsHash()).isEqualTo("deadbeef");
        assertThat(reloaded.getLastPostedAt()).isEqualTo(Instant.parse("2026-05-10T00:00:00Z"));
    }

    @Test
    void roundTripsOriginalMessageIdAndPostedAlertIds() {
        long guildId = TestIds.nextLong();
        Event e = fixtures.seedEvent(
                guildId, "123456789", "Test Event", ZonedDateTime.now().plusDays(1), ev -> {});
        TfnswEventSnapshot snap = new TfnswEventSnapshot();
        snap.setEventId(e.getId());
        snap.setAlertIdsHash("hash-1");
        snap.setOriginalMessageId(1_234_567_890L);
        snap.setPostedAlertIds("alert-a\nalert-b\nalert-c");
        repo.save(snap);

        TfnswEventSnapshot reloaded = repo.findById(e.getId()).orElseThrow();
        assertThat(reloaded.getOriginalMessageId()).isEqualTo(1_234_567_890L);
        assertThat(reloaded.getPostedAlertIds()).isEqualTo("alert-a\nalert-b\nalert-c");
    }
}
