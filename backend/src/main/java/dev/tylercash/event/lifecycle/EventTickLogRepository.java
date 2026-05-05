package dev.tylercash.event.lifecycle;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EventTickLogRepository extends JpaRepository<EventTickLog, EventTickLogId> {}
