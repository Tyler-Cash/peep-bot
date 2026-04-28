package dev.tylercash.event.rewind;

import dev.tylercash.event.discord.DiscordUserCacheService;
import dev.tylercash.event.rewind.model.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class RewindService {

    private final DiscordUserCacheService userCacheService;
    private final EmbeddingService embeddingService;

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

    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public List<Integer> getYears(long guildId) {
        return em.createNativeQuery(
                        "SELECT DISTINCT EXTRACT(YEAR FROM date_time)::int FROM event WHERE server_id = :guildId AND state != 'CANCELLED' ORDER BY 1 DESC")
                .setParameter("guildId", guildId)
                .getResultList();
    }

    @SuppressWarnings("unchecked")
    private RewindStatsDto buildStats(String snowflake, Integer year, long guildId) {
        boolean personal = snowflake != null;
        String yf = year != null ? " AND EXTRACT(YEAR FROM e.date_time) = :year" : "";
        String gf = " AND e.server_id = :guildId AND e.state != 'CANCELLED'";

        // Total events
        String totalEventsQ = personal
                ? "SELECT COUNT(DISTINCT a.event_id) FROM attendance a "
                        + "JOIN event e ON a.event_id = e.id "
                        + "WHERE a.snowflake = :snowflake AND a.status = 'ACCEPTED'" + yf + gf
                : "SELECT COUNT(*) FROM event e WHERE 1=1" + yf + gf;
        var teq = em.createNativeQuery(totalEventsQ);
        if (year != null) teq.setParameter("year", year);
        if (personal) teq.setParameter("snowflake", snowflake);
        teq.setParameter("guildId", guildId);
        int totalEvents = ((Number) teq.getSingleResult()).intValue();

        // Total unique attendees
        String uniqueQ = personal
                ? "SELECT COUNT(DISTINCT a.snowflake) FROM attendance a "
                        + "JOIN event e ON a.event_id = e.id "
                        + "JOIN attendance a2 ON a.event_id = a2.event_id "
                        + "WHERE a.status = 'ACCEPTED' AND a.snowflake IS NOT NULL "
                        + "AND a2.snowflake = :snowflake AND a2.status = 'ACCEPTED'" + yf + gf
                : "SELECT COUNT(DISTINCT a.snowflake) FROM attendance a "
                        + "JOIN event e ON a.event_id = e.id "
                        + "WHERE a.status = 'ACCEPTED' AND a.snowflake IS NOT NULL" + yf + gf;
        var uq = em.createNativeQuery(uniqueQ);
        if (year != null) uq.setParameter("year", year);
        if (personal) uq.setParameter("snowflake", snowflake);
        uq.setParameter("guildId", guildId);
        int totalUniqueAttendees = ((Number) uq.getSingleResult()).intValue();

        // Total RSVPs
        String rsvpQ = personal
                ? "SELECT COUNT(*) FROM attendance a "
                        + "JOIN event e ON a.event_id = e.id "
                        + "WHERE a.snowflake = :snowflake AND a.status != 'REMOVED'" + yf + gf
                : "SELECT COUNT(*) FROM attendance a " + "JOIN event e ON a.event_id = e.id WHERE a.status != 'REMOVED'"
                        + yf + gf;
        var rq = em.createNativeQuery(rsvpQ);
        if (year != null) rq.setParameter("year", year);
        if (personal) rq.setParameter("snowflake", snowflake);
        rq.setParameter("guildId", guildId);
        int totalRsvps = ((Number) rq.getSingleResult()).intValue();

        // Average group size (guild-wide: avg accepted per event)
        String avgQ = "SELECT COALESCE(AVG(cnt), 0) FROM ("
                + "SELECT COUNT(*) as cnt FROM attendance a "
                + "JOIN event e ON a.event_id = e.id "
                + "WHERE a.status = 'ACCEPTED' AND a.snowflake IS NOT NULL" + yf + gf
                + " GROUP BY a.event_id) sub";
        var agq = em.createNativeQuery(avgQ);
        if (year != null) agq.setParameter("year", year);
        agq.setParameter("guildId", guildId);
        double averageGroupSize = ((Number) agq.getSingleResult()).doubleValue();

        // Top categories
        String catQ = personal
                ? "SELECT ec.category_label, COUNT(DISTINCT e.id) as ec, COUNT(DISTINCT a.snowflake) as ta "
                        + "FROM event_category ec "
                        + "JOIN event e ON ec.event_id = e.id "
                        + "JOIN attendance a ON a.event_id = e.id "
                        + "AND a.status = 'ACCEPTED' AND a.snowflake IS NOT NULL "
                        + "WHERE a.snowflake = :snowflake" + yf + gf
                        + " GROUP BY ec.category_label ORDER BY ec DESC LIMIT 10"
                : "SELECT ec.category_label, COUNT(DISTINCT e.id) as ec, COUNT(DISTINCT a.snowflake) as ta "
                        + "FROM event_category ec "
                        + "JOIN event e ON ec.event_id = e.id "
                        + "LEFT JOIN attendance a ON a.event_id = e.id "
                        + "AND a.status = 'ACCEPTED' AND a.snowflake IS NOT NULL "
                        + "WHERE 1=1" + yf + gf
                        + " GROUP BY ec.category_label ORDER BY ec DESC LIMIT 10";
        var cq = em.createNativeQuery(catQ);
        if (year != null) cq.setParameter("year", year);
        if (personal) cq.setParameter("snowflake", snowflake);
        cq.setParameter("guildId", guildId);
        List<EventCategoryDto> topCategories = ((List<Object[]>) cq.getResultList())
                .stream()
                        .map(r -> new EventCategoryDto(
                                (String) r[0], ((Number) r[1]).intValue(), ((Number) r[2]).intValue()))
                        .collect(Collectors.toList());

        // Top attendees
        String attendeeQ = "SELECT a.snowflake, COUNT(DISTINCT a.event_id) as cnt "
                + "FROM attendance a JOIN event e ON a.event_id = e.id "
                + "WHERE a.status = 'ACCEPTED' AND a.snowflake IS NOT NULL" + yf + gf
                + " GROUP BY a.snowflake ORDER BY cnt DESC LIMIT 10";
        var aq = em.createNativeQuery(attendeeQ);
        if (year != null) aq.setParameter("year", year);
        aq.setParameter("guildId", guildId);
        List<Object[]> attendeeRows = aq.getResultList();
        Set<String> attendeeSnowflakes =
                attendeeRows.stream().map(r -> (String) r[0]).collect(Collectors.toSet());
        Map<String, String> attendeeNames = userCacheService.getDisplayNames(guildId, attendeeSnowflakes);
        List<AttendeeStatDto> topAttendees = attendeeRows.stream()
                .map(r -> {
                    String attendeeSnowflake = (String) r[0];
                    String name = attendeeNames.getOrDefault(attendeeSnowflake, "Unknown");
                    String avatarUrl = "/api/avatar/" + attendeeSnowflake;
                    return new AttendeeStatDto(name, ((Number) r[1]).intValue(), avatarUrl);
                })
                .collect(Collectors.toList());

        // Top organizers
        String orgQ = "SELECT e.creator, COUNT(*) as cnt FROM event e WHERE 1=1" + yf + gf
                + " GROUP BY e.creator ORDER BY cnt DESC LIMIT 10";
        var oq = em.createNativeQuery(orgQ);
        if (year != null) oq.setParameter("year", year);
        oq.setParameter("guildId", guildId);
        List<Object[]> orgRows = oq.getResultList();
        Set<String> orgSnowflakes = orgRows.stream()
                .map(r -> (String) r[0])
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.toSet());
        Map<String, String> orgNames = userCacheService.getDisplayNames(guildId, orgSnowflakes);
        List<AttendeeStatDto> topOrganizers = orgRows.stream()
                .map(r -> {
                    String raw = (String) r[0];
                    String name = orgNames.getOrDefault(raw, raw != null && !raw.isBlank() ? raw : "Unknown");
                    String avatarUrl = (raw != null && !raw.isBlank()) ? "/api/avatar/" + raw : null;
                    return new AttendeeStatDto(name, ((Number) r[1]).intValue(), avatarUrl);
                })
                .collect(Collectors.toList());

        // Social graph (guild-wide only). One round-trip: a CTE deduplicates
        // (event_id, snowflake) so duplicate attendance rows can't double-count, and
        // the result set merges edges and node degrees via UNION ALL.
        SocialGraphDto socialGraph = null;
        if (!personal) {
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

            Map<String, String> nodeNames = userCacheService.getDisplayNames(guildId, graphSnowflakes);
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

            socialGraph = new SocialGraphDto(nodes, edges);
        }

        // Events by month
        String monthQ = personal
                ? "SELECT TO_CHAR(e.date_time, 'YYYY-MM') as month, COUNT(DISTINCT e.id) as cnt "
                        + "FROM event e JOIN attendance a ON a.event_id = e.id "
                        + "WHERE a.snowflake = :snowflake AND a.status = 'ACCEPTED'" + yf + gf
                        + " GROUP BY month ORDER BY month"
                : "SELECT TO_CHAR(e.date_time, 'YYYY-MM') as month, COUNT(*) as cnt "
                        + "FROM event e WHERE 1=1" + yf + gf
                        + " GROUP BY month ORDER BY month";
        var mq = em.createNativeQuery(monthQ);
        if (year != null) mq.setParameter("year", year);
        if (personal) mq.setParameter("snowflake", snowflake);
        mq.setParameter("guildId", guildId);
        Map<String, Integer> eventsByMonth = new LinkedHashMap<>();
        ((List<Object[]>) mq.getResultList())
                .forEach(r -> eventsByMonth.put((String) r[0], ((Number) r[1]).intValue()));

        // Events by day of week
        String[] dayNames = {"Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"};
        String dowQ = personal
                ? "SELECT EXTRACT(DOW FROM e.date_time)::int as dow, COUNT(DISTINCT e.id) as cnt "
                        + "FROM event e JOIN attendance a ON a.event_id = e.id "
                        + "WHERE a.snowflake = :snowflake AND a.status = 'ACCEPTED'" + yf + gf
                        + " GROUP BY dow ORDER BY dow"
                : "SELECT EXTRACT(DOW FROM e.date_time)::int as dow, COUNT(*) as cnt "
                        + "FROM event e WHERE 1=1" + yf + gf
                        + " GROUP BY dow ORDER BY dow";
        var dq = em.createNativeQuery(dowQ);
        if (year != null) dq.setParameter("year", year);
        if (personal) dq.setParameter("snowflake", snowflake);
        dq.setParameter("guildId", guildId);
        Map<String, Integer> eventsByDayOfWeek = new LinkedHashMap<>();
        ((List<Object[]>) dq.getResultList())
                .forEach(r -> eventsByDayOfWeek.put(dayNames[((Number) r[0]).intValue()], ((Number) r[1]).intValue()));

        // First event
        String firstQ = personal
                ? "SELECT e.id, e.name, e.date_time FROM event e "
                        + "JOIN attendance a ON a.event_id = e.id "
                        + "WHERE a.snowflake = :snowflake AND a.status = 'ACCEPTED' AND e.date_time IS NOT NULL" + yf
                        + gf
                        + " ORDER BY e.date_time ASC LIMIT 1"
                : "SELECT e.id, e.name, e.date_time FROM event e WHERE e.date_time IS NOT NULL" + yf + gf
                        + " ORDER BY e.date_time ASC LIMIT 1";
        var feq = em.createNativeQuery(firstQ);
        if (year != null) feq.setParameter("year", year);
        if (personal) feq.setParameter("snowflake", snowflake);
        feq.setParameter("guildId", guildId);
        List<Object[]> firstRows = feq.getResultList();
        EventSummaryDto firstEvent = firstRows.isEmpty() ? null : toEventSummary(firstRows.get(0));

        // Last event
        String lastQ = personal
                ? "SELECT e.id, e.name, e.date_time FROM event e "
                        + "JOIN attendance a ON a.event_id = e.id "
                        + "WHERE a.snowflake = :snowflake AND a.status = 'ACCEPTED' AND e.date_time IS NOT NULL" + yf
                        + gf
                        + " ORDER BY e.date_time DESC LIMIT 1"
                : "SELECT e.id, e.name, e.date_time FROM event e WHERE e.date_time IS NOT NULL" + yf + gf
                        + " ORDER BY e.date_time DESC LIMIT 1";
        var leq = em.createNativeQuery(lastQ);
        if (year != null) leq.setParameter("year", year);
        if (personal) leq.setParameter("snowflake", snowflake);
        leq.setParameter("guildId", guildId);
        List<Object[]> lastRows = leq.getResultList();
        EventSummaryDto lastEvent = lastRows.isEmpty() ? null : toEventSummary(lastRows.get(0));

        // Total +1 guests
        String plusQ = personal
                ? "SELECT COUNT(*) FROM attendance a "
                        + "JOIN event e ON a.event_id = e.id "
                        + "WHERE a.snowflake IS NULL AND a.status = 'ACCEPTED' AND a.owner_snowflake = :snowflake" + yf
                        + gf
                : "SELECT COUNT(*) FROM attendance a "
                        + "JOIN event e ON a.event_id = e.id "
                        + "WHERE a.snowflake IS NULL AND a.status = 'ACCEPTED'" + yf + gf;
        var pog = em.createNativeQuery(plusQ);
        if (year != null) pog.setParameter("year", year);
        if (personal) pog.setParameter("snowflake", snowflake);
        pog.setParameter("guildId", guildId);
        int totalPlusOneGuests = ((Number) pog.getSingleResult()).intValue();

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

    private EventSummaryDto toEventSummary(Object[] row) {
        UUID id = (UUID) row[0];
        String name = (String) row[1];
        ZonedDateTime dateTime;
        Object dtObj = row[2];
        if (dtObj instanceof java.sql.Timestamp ts) {
            dateTime = ts.toInstant().atZone(ZoneOffset.UTC);
        } else if (dtObj instanceof java.time.OffsetDateTime odt) {
            dateTime = odt.toZonedDateTime();
        } else {
            log.warn(
                    "Unexpected date_time type: {}",
                    dtObj == null ? "null" : dtObj.getClass().getName());
            dateTime = ZonedDateTime.now();
        }
        return new EventSummaryDto(id, name, dateTime);
    }
}
