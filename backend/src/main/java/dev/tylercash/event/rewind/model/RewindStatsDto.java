package dev.tylercash.event.rewind.model;

import java.util.List;
import java.util.Map;

public record RewindStatsDto(
        int totalEvents,
        int totalUniqueAttendees,
        int totalRsvps,
        double averageGroupSize,
        List<EventCategoryDto> topCategories,
        List<AttendeeStatDto> topAttendees,
        List<AttendeeStatDto> topOrganizers,
        SocialGraphDto socialGraph,
        Map<String, Integer> eventsByMonth,
        Map<String, Integer> eventsByDayOfWeek,
        EventSummaryDto firstEvent,
        EventSummaryDto lastEvent,
        int totalPlusOneGuests,
        boolean embeddingsAvailable,
        Integer year) {}
