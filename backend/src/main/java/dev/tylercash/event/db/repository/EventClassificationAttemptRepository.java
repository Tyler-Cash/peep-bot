package dev.tylercash.event.db.repository;

import dev.tylercash.event.event.EventClassificationAttempt;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface EventClassificationAttemptRepository extends JpaRepository<EventClassificationAttempt, UUID> {}
