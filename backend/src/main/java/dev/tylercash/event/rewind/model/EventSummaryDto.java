package dev.tylercash.event.rewind.model;

import java.time.ZonedDateTime;
import java.util.UUID;

public record EventSummaryDto(UUID id, String name, ZonedDateTime dateTime) {}
