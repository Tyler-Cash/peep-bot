package dev.tylercash.event.db.repository;

import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EventRepository extends JpaRepository<Event, UUID> {
    Page<Event> findAll(Pageable pageable);

    Page<Event> findAllByStateEquals(Pageable pageable, EventState state);

    Page<Event> findAllByStateNotIn(Pageable pageable, List<EventState> states);

    Page<Event> findAllByStateIn(Pageable pageable, List<EventState> states);

    Optional<Event> findById(UUID id);

    Event findByMessageId(Long id);

    Event findByChannelId(Long id);

    Page<Event> findByState(Pageable pageable, EventState state);
}
