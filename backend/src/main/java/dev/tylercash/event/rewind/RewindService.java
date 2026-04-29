package dev.tylercash.event.rewind;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordUserCacheService;
import dev.tylercash.event.rewind.model.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class RewindService {

    private static final String[] DAY_NAMES = {
        "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"
    };

    private final DiscordUserCacheService userCacheService;
    private final EmbeddingService embeddingService;
    private final EventRepository eventRepository;

    @PersistenceContext
    private EntityManager em;

    @Cacheable(value = "rewind", key = "'guild-' + #guildId + '-' + #year")
    @Transactional(readOnly = true)
    public RewindStatsDto getGuildStats(long guildId, Integer year) {
        return buildStats(null, year, guildId);
    }

    @Cacheable(value = "rewind", key = "'user-' + #snowflake + '-' + #guildId + '-' + #year")
    @Transactional(readOnly = true)
    public RewindStatsDto getUserStats(String snowflake, long guildId, Integer year) {
        return buildStats(snowflake, year, guildId);
    }

    @Transactional(readOnly = true)
    public List<Integer> getYears(long guildId) {
        return eventRepository.findDistinctYearsForGuild(guildId);
    }

    private RewindStatsDto buildStats(String snowflake, Integer year, long guildId) {
        boolean personal = snowflake != null;

        int totalEvents = personal
                ? (int) eventRepository.countTotalEventsPersonal(snowflake, guildId, year)
                : (int) eventRepository.countTotalEventsGuildWide(guildId, year);

        int totalUniqueAttendees = personal
                ? (int) eventRepository.countUniqueAttendeesPersonal(snowflake, guildId, year)
                : (int) eventRepository.countUniqueAttendeesGuildWide(guildId, year);

        int totalRsvps = personal
                ? (int) eventRepository.countTotalRsvpsPersonal(snowflake, guildId, year)
                : (int) eventRepository.countTotalRsvpsGuildWide(guildId, year);

        double averageGroupSize = computeAverageGroupSize(year, guildId);

        Pageable top10 = Pageable.ofSize(10);

        List<Object[]> categoryRows = personal
                ? eventRepository.findTopCategoriesPersonal(snowflake, guildId, year, top10)
                : eventRepository.findTopCategoriesGuildWide(guildId, year, top10);
        List<EventCategoryDto> topCategories = categoryRows.stream()
                .map(r -> new EventCategoryDto((String) r[0], ((Number) r[1]).intValue(), ((Number) r[2]).intValue()))
                .collect(Collectors.toList());

        List<Object[]> attendeeRows = eventRepository.findTopAttendees(guildId, year, top10);
        Set<String> attendeeSnowflakes =
                attendeeRows.stream().map(r -> (String) r[0]).collect(Collectors.toSet());
        Map<String, String> attendeeNames = userCacheService.getDisplayNames(attendeeSnowflakes);
        List<AttendeeStatDto> topAttendees = attendeeRows.stream()
                .map(r -> {
                    String attendeeSnowflake = (String) r[0];
                    String name = attendeeNames.getOrDefault(attendeeSnowflake, "Unknown");
                    return new AttendeeStatDto(name, ((Number) r[1]).intValue(), "/api/avatar/" + attendeeSnowflake);
                })
                .collect(Collectors.toList());

        List<Object[]> orgRows = eventRepository.findTopOrganizers(guildId, year, top10);
        Set<String> orgSnowflakes = orgRows.stream()
                .map(r -> (String) r[0])
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.toSet());
        Map<String, String> orgNames = userCacheService.getDisplayNames(orgSnowflakes);
        List<AttendeeStatDto> topOrganizers = orgRows.stream()
                .map(r -> {
                    String raw = (String) r[0];
                    String name = orgNames.getOrDefault(raw, raw != null && !raw.isBlank() ? raw : "Unknown");
                    String avatarUrl = (raw != null && !raw.isBlank()) ? "/api/avatar/" + raw : null;
                    return new AttendeeStatDto(name, ((Number) r[1]).intValue(), avatarUrl);
                })
                .collect(Collectors.toList());

        SocialGraphDto socialGraph = personal ? null : buildSocialGraph(year, guildId);

        List<Object[]> monthRows = personal
                ? eventRepository.findEventsByMonthPersonal(snowflake, guildId, year)
                : eventRepository.findEventsByMonthGuildWide(guildId, year);
        Map<String, Integer> eventsByMonth = new LinkedHashMap<>();
        monthRows.forEach(r -> eventsByMonth.put((String) r[0], ((Number) r[1]).intValue()));

        // HQL `extract(day of week from ...)` returns 1..7 (Sun=1) per JDBC; map to 0..6 for our day-name array.
        List<Object[]> dowRows = personal
                ? eventRepository.findEventsByDayOfWeekPersonal(snowflake, guildId, year)
                : eventRepository.findEventsByDayOfWeekGuildWide(guildId, year);
        Map<String, Integer> eventsByDayOfWeek = new LinkedHashMap<>();
        dowRows.forEach(
                r -> eventsByDayOfWeek.put(DAY_NAMES[((Number) r[0]).intValue() - 1], ((Number) r[1]).intValue()));

        Pageable top1 = Pageable.ofSize(1);
        EventSummaryDto firstEvent = (personal
                        ? eventRepository.findFirstEventPersonal(snowflake, guildId, year, top1)
                        : eventRepository.findFirstEventGuildWide(guildId, year, top1))
                .stream().findFirst().orElse(null);
        EventSummaryDto lastEvent = (personal
                        ? eventRepository.findLastEventPersonal(snowflake, guildId, year, top1)
                        : eventRepository.findLastEventGuildWide(guildId, year, top1))
                .stream().findFirst().orElse(null);

        int totalPlusOneGuests = personal
                ? (int) eventRepository.countPlusOnesPersonal(snowflake, guildId, year)
                : (int) eventRepository.countPlusOnesGuildWide(guildId, year);

        return new RewindStatsDto(
                totalEvents,
                totalUniqueAttendees,
                totalRsvps,
                averageGroupSize,
                topCategories,
                topAttendees,
                topOrganizers,
                socialGraph,
                eventsByMonth,
                eventsByDayOfWeek,
                firstEvent,
                lastEvent,
                totalPlusOneGuests,
                embeddingService.isEmbeddingsAvailable(),
                year);
    }

    /**
     * Average accepted-attendees per event (guild-wide). Kept native because JPQL/HQL has no
     * subquery-in-FROM, so the natural {@code AVG(COUNT(*) GROUP BY ...)} pattern requires either a
     * derived table or two round-trips.
     */
    @SuppressWarnings("unchecked")
    private double computeAverageGroupSize(Integer year, long guildId) {
        String yf = year != null ? " AND EXTRACT(YEAR FROM e.date_time) = :year" : "";
        String avgQ = "SELECT COALESCE(AVG(cnt), 0) FROM ("
                + "SELECT COUNT(*) as cnt FROM attendance a "
                + "JOIN event e ON a.event_id = e.id "
                + "WHERE a.status = 'ACCEPTED' AND a.snowflake IS NOT NULL"
                + yf
                + " AND e.server_id = :guildId AND e.state != 'CANCELLED'"
                + " GROUP BY a.event_id) sub";
        var q = em.createNativeQuery(avgQ);
        if (year != null) q.setParameter("year", year);
        q.setParameter("guildId", guildId);
        return ((Number) q.getSingleResult()).doubleValue();
    }

    /**
     * Social graph (guild-wide). Kept native: the CTE deduplicates {@code (event_id, snowflake)} so
     * duplicate accepted rows can't double-count, and {@code UNION ALL} merges edges + node degrees
     * in a single round-trip — neither construct exists in JPQL/HQL.
     */
    @SuppressWarnings("unchecked")
    private SocialGraphDto buildSocialGraph(Integer year, long guildId) {
        String yf = year != null ? " AND EXTRACT(YEAR FROM e.date_time) = :year" : "";
        String gf = " AND e.server_id = :guildId AND e.state != 'CANCELLED'";
        String graphQ = "WITH accepted AS ("
                + "  SELECT DISTINCT a.event_id, a.snowflake "
                + "  FROM attendance a JOIN event e ON a.event_id = e.id "
                + "  WHERE a.status = 'ACCEPTED' AND a.snowflake IS NOT NULL" + yf + gf
                + "), pairs AS ("
                + "  SELECT a1.snowflake AS u1, a2.snowflake AS u2, COUNT(*) AS shared "
                + "  FROM accepted a1 JOIN accepted a2 "
                + "    ON a1.event_id = a2.event_id AND a1.snowflake < a2.snowflake "
                + "  GROUP BY a1.snowflake, a2.snowflake"
                + "), node_set AS ("
                + "  SELECT u1 AS snowflake FROM pairs UNION SELECT u2 FROM pairs"
                + "), node_counts AS ("
                + "  SELECT a.snowflake, COUNT(*) AS cnt FROM accepted a "
                + "  WHERE a.snowflake IN (SELECT snowflake FROM node_set) "
                + "  GROUP BY a.snowflake"
                + ") "
                + "SELECT 'edge' AS kind, u1 AS s1, u2 AS s2, shared AS cnt FROM pairs "
                + "UNION ALL "
                + "SELECT 'node' AS kind, snowflake AS s1, NULL AS s2, cnt FROM node_counts";
        var gq = em.createNativeQuery(graphQ);
        if (year != null) gq.setParameter("year", year);
        gq.setParameter("guildId", guildId);
        List<Object[]> graphRows = gq.getResultList();

        List<GraphEdgeDto> edges = new ArrayList<>();
        List<Object[]> nodeRows = new ArrayList<>();
        Set<String> graphSnowflakes = new HashSet<>();
        for (Object[] r : graphRows) {
            if ("edge".equals(r[0])) {
                String u1 = (String) r[1];
                String u2 = (String) r[2];
                edges.add(new GraphEdgeDto(u1, u2, ((Number) r[3]).intValue()));
            } else {
                String s = (String) r[1];
                graphSnowflakes.add(s);
                nodeRows.add(r);
            }
        }
        edges.sort((a, b) -> Integer.compare(b.sharedEvents(), a.sharedEvents()));

        Map<String, String> nodeNames = userCacheService.getDisplayNames(graphSnowflakes);
        List<GraphNodeDto> nodes = nodeRows.stream()
                .map(r -> {
                    String nodeSnowflake = (String) r[1];
                    return new GraphNodeDto(
                            nodeSnowflake,
                            nodeNames.getOrDefault(nodeSnowflake, "Unknown"),
                            "/api/avatar/" + nodeSnowflake,
                            ((Number) r[3]).intValue());
                })
                .collect(Collectors.toList());

        return new SocialGraphDto(nodes, edges);
    }
}
