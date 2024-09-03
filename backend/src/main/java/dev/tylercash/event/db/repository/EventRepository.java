package dev.tylercash.event.db.repository;

import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EventRepository extends CrudRepository<Event, UUID> {
    List<Event> findAll();

    Optional<Event> findById(UUID id);

    Event findByMessageId(Long id);

    Event findByChannelId(Long id);

    List<Event> findByState(EventState state);
}
