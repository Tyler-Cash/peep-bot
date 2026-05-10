package dev.tylercash.event.event;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.test.AbstractHttpIntegrationTest;
import dev.tylercash.event.test.TestIds;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class EventCoordsPersistenceTest extends AbstractHttpIntegrationTest {

    @Autowired
    EventRepository events;

    @Test
    void persistsLatLng() {
        long guildId = TestIds.nextLong();
        String creator = TestIds.nextSnowflake();
        Event seeded = fixtures.seedEvent(
                guildId, creator, "Coords Test Event", ZonedDateTime.now().plusDays(1), e -> {
                    e.setLocationLat(-33.8688);
                    e.setLocationLng(151.2093);
                });
        Event reloaded = events.findById(seeded.getId()).orElseThrow();
        assertThat(reloaded.getLocationLat()).isEqualTo(-33.8688);
        assertThat(reloaded.getLocationLng()).isEqualTo(151.2093);
    }
}
