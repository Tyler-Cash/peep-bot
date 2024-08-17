package dev.tylercash.event.db.repository;

import dev.tylercash.event.event.model.Attendee;
import dev.tylercash.event.event.model.Event;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.EmbeddedDatabaseConnection;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Profile;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

@NoArgsConstructor
@SpringBootTest
@Profile("integrationtest")
@AutoConfigureTestDatabase(connection = EmbeddedDatabaseConnection.H2)
class EventRepositoryTest {
    @Autowired
    private EventRepository eventRepository;

    @Test
    void testSave() {
        Event event = new Event(1, 2, 3, "description", "", LocalDateTime.now());
        event.getAccepted().add(new Attendee("", "user1", Instant.now()));
        eventRepository.save(event);
        assertEquals(event, eventRepository.findAll().get(0));
    }
}