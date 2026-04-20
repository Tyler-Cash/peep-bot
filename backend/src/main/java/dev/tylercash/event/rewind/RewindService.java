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

    @Cacheable(value = "rewind", key = "'guild-' + #year")
    @Transactional(readOnly = true)
    public RewindStatsDto getGuildStats(Integer year) {
        return buildStats(null, year);
    }

    @Cacheable(value = "rewind", key = "'user-' + #snowflake + '-' + #year")
    @Transactional(readOnly = true)
    public RewindStatsDto getUserStats(String snowflake, Integer year) {
        return buildStats(snowflake, year);
    }

    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public List<Integer> getYears() {
        return em.createNativeQuery("SELECT DISTINCT EXTRACT(YEAR FROM date_time)::int FROM event ORDER BY 1 DESC")
                .getResultList();
    }

    @SuppressWarnings("unchecked")
    private RewindStatsDto buildStats(String snowflake, Integer year) {
        boolean personal = snowflake != null;
        String yf = year != null ? " AND EXTRACT(YEAR FROM e.date_time) = :year" : "";

        // Total events
        String totalEventsQ = personal
                ? "SELECT COUNT(DISTINCT a.event_id) FROM attendance a "
                        + "JOIN event e ON a.event_id = e.id "
                        + "WHERE a.snowflake = :snowflake AND a.status = 'ACCEPTED'" + yf
                : "SELECT COUNT(*) FROM event e WHERE 1=1" + yf;
        var teq = em.createNativeQuery(totalEventsQ);
        if (year != null) teq.setParameter("year", year);
        if (personal) teq.setParameter("snowflake", snowflake);
        int totalEvents = ((Number) teq.getSingleResult()).intValue();

        // Total unique attendees
        String uniqueQ = personal
                ? "SELECT COUNT(DISTINCT a.snowflake) FROM attendance a "
                        + "JOIN event e ON a.event_id = e.id "
                        + "JOIN attendance a2 ON a.event_id = a2.event_id "
                        + "WHERE a.status = 'ACCEPTED' AND a.snowflake IS NOT NULL "
                        + "AND a2.snowflake = :snowflake AND a2.status = 'ACCEPTED'" + yf
                : "SELECT COUNT(DISTINCT a.snowflake) FROM attendance a "
                        + "JOIN event e ON a.event_id = e.id "
                        + "WHERE a.status = 'ACCEPTED' AND a.snowflake IS NOT NULL" + yf;
        var uq = em.createNativeQuery(uniqueQ);
        if (year != null) uq.setParameter("year", year);
        if (personal) uq.setParameter("snowflake", snowflake);
        int totalUniqueAttendees = ((Number) uq.getSingleResult()).intValue();

        // Total RSVPs
        String rsvpQ = personal
                ? "SELECT COUNT(*) FROM attendance a "
                        + "JOIN event e ON a.event_id = e.id "
                        + "WHERE a.snowflake = :snowflake AND a.status != 'REMOVED'" + yf
                : "SELECT COUNT(*) FROM attendance a " + "JOIN event e ON a.event_id = e.id WHERE a.status != 'REMOVED'"
                        + yf;
        var rq = em.createNativeQuery(rsvpQ);
        if (year != null) rq.setParameter("year", year);
        if (personal) rq.setParameter("snowflake", snowflake);
        int totalRsvps = ((Number) rq.getSingleResult()).intValue();

        // Average group size (guild-wide: avg accepted per event)
        String avgQ = "SELECT COALESCE(AVG(cnt), 0) FROM ("
                + "SELECT COUNT(*) as cnt FROM attendance a "
                + "JOIN event e ON a.event_id = e.id "
                + "WHERE a.status = 'ACCEPTED' AND a.snowflake IS NOT NULL" + yf
                + " GROUP BY a.event_id) sub";
        var agq = em.createNativeQuery(avgQ);
        if (year != null) agq.setParameter("year", year);
        double averageGroupSize = ((Number) agq.getSingleResult()).doubleValue();

        // Top categories
        String catQ = personal
                ? "SELECT ec.category_label, COUNT(DISTINCT e.id) as ec, COUNT(DISTINCT a.snowflake) as ta "
                        + "FROM event_category ec "
                        + "JOIN event e ON ec.event_id = e.id "
                        + "JOIN attendance a ON a.event_id = e.id "
                        + "AND a.status = 'ACCEPTED' AND a.snowflake IS NOT NULL "
                        + "WHERE a.snowflake = :snowflake" + yf
                        + " GROUP BY ec.category_label ORDER BY ec DESC LIMIT 10"
                : "SELECT ec.category_label, COUNT(DISTINCT e.id) as ec, COUNT(DISTINCT a.snowflake) as ta "
                        + "FROM event_category ec "
                        + "JOIN event e ON ec.event_id = e.id "
                        + "LEFT JOIN attendance a ON a.event_id = e.id "
                        + "AND a.status = 'ACCEPTED' AND a.snowflake IS NOT NULL "
                        + "WHERE 1=1" + yf
                        + " GROUP BY ec.category_label ORDER BY ec DESC LIMIT 10";
        var cq = em.createNativeQuery(catQ);
        if (year != null) cq.setParameter("year", year);
        if (personal) cq.setParameter("snowflake", snowflake);
        List<EventCategoryDto> topCategories = ((List<Object[]>) cq.getResultList())
                .stream()
                        .map(r -> new EventCategoryDto(
                                (String) r[0], ((Number) r[1]).intValue(), ((Number) r[2]).intValue()))
                        .collect(Collectors.toList());

        // Top attendees
        String attendeeQ = "SELECT a.snowflake, COUNT(DISTINCT a.event_id) as cnt "
                + "FROM attendance a JOIN event e ON a.event_id = e.id "
                + "WHERE a.status = 'ACCEPTED' AND a.snowflake IS NOT NULL" + yf
                + " GROUP BY a.snowflake ORDER BY cnt DESC LIMIT 10";
        var aq = em.createNativeQuery(attendeeQ);
        if (year != null) aq.setParameter("year", year);
        List<Object[]> attendeeRows = aq.getResultList();
        Set<String> attendeeSnowflakes =
                attendeeRows.stream().map(r -> (String) r[0]).collect(Collectors.toSet());
        Map<String, String> attendeeNames = userCacheService.getDisplayNames(attendeeSnowflakes);
        List<AttendeeStatDto> topAttendees = attendeeRows.stream()
                .map(r -> new AttendeeStatDto(
                        attendeeNames.getOrDefault((String) r[0], "Unknown"), ((Number) r[1]).intValue()))
                .collect(Collectors.toList());

        // Top organizers
        String orgQ = "SELECT e.creator, COUNT(*) as cnt FROM event e WHERE 1=1" + yf
                + " GROUP BY e.creator ORDER BY cnt DESC LIMIT 10";
        var oq = em.createNativeQuery(orgQ);
        if (year != null) oq.setParameter("year", year);
        List<Object[]> orgRows = oq.getResultList();
        Set<String> orgSnowflakes = orgRows.stream()
                .map(r -> (String) r[0])
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.toSet());
        Map<String, String> orgNames = userCacheService.getDisplayNames(orgSnowflakes);
        List<AttendeeStatDto> topOrganizers = orgRows.stream()
                .map(r -> new AttendeeStatDto(
                        orgNames.getOrDefault((String) r[0], "Unknown"), ((Number) r[1]).intValue()))
                .collect(Collectors.toList());

        // Social pairs (guild-wide only)
        List<SocialPairDto> topSocialPairs = new ArrayList<>();
        if (!personal) {
            String pairsQ = "SELECT a1.snowflake AS u1, a2.snowflake AS u2, COUNT(DISTINCT a1.event_id) AS shared "
                    + "FROM attendance a1 "
                    + "JOIN attendance a2 ON a1.event_id = a2.event_id AND a1.snowflake < a2.snowflake "
                    + "JOIN event e ON a1.event_id = e.id "
                    + "WHERE a1.status = 'ACCEPTED' AND a2.status = 'ACCEPTED'" + yf
                    + " GROUP BY a1.snowflake, a2.snowflake ORDER BY shared DESC LIMIT 20";
            var pq = em.createNativeQuery(pairsQ);
            if (year != null) pq.setParameter("year", year);
            List<Object[]> pairRows = pq.getResultList();
            Set<String> pairSnowflakes = new HashSet<>();
            pairRows.forEach(r -> {
                pairSnowflakes.add((String) r[0]);
                pairSnowflakes.add((String) r[1]);
            });
            Map<String, String> pairNames = userCacheService.getDisplayNames(pairSnowflakes);
            topSocialPairs = pairRows.stream()
                    .map(r -> new SocialPairDto(
                            pairNames.getOrDefault((String) r[0], "Unknown"),
                            pairNames.getOrDefault((String) r[1], "Unknown"),
                            ((Number) r[2]).intValue()))
                    .collect(Collectors.toList());
        }

        // Events by month
        String monthQ = personal
                ? "SELECT TO_CHAR(e.date_time, 'YYYY-MM') as month, COUNT(DISTINCT e.id) as cnt "
                        + "FROM event e JOIN attendance a ON a.event_id = e.id "
                        + "WHERE a.snowflake = :snowflake AND a.status = 'ACCEPTED'" + yf
                        + " GROUP BY month ORDER BY month"
                : "SELECT TO_CHAR(e.date_time, 'YYYY-MM') as month, COUNT(*) as cnt " + "FROM event e WHERE 1=1" + yf
                        + " GROUP BY month ORDER BY month";
        var mq = em.createNativeQuery(monthQ);
        if (year != null) mq.setParameter("year", year);
        if (personal) mq.setParameter("snowflake", snowflake);
        Map<String, Integer> eventsByMonth = new LinkedHashMap<>();
        ((List<Object[]>) mq.getResultList())
                .forEach(r -> eventsByMonth.put((String) r[0], ((Number) r[1]).intValue()));

        // Events by day of week
        String[] dayNames = {"Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"};
        String dowQ = personal
                ? "SELECT EXTRACT(DOW FROM e.date_time)::int as dow, COUNT(DISTINCT e.id) as cnt "
                        + "FROM event e JOIN attendance a ON a.event_id = e.id "
                        + "WHERE a.snowflake = :snowflake AND a.status = 'ACCEPTED'" + yf
                        + " GROUP BY dow ORDER BY dow"
                : "SELECT EXTRACT(DOW FROM e.date_time)::int as dow, COUNT(*) as cnt " + "FROM event e WHERE 1=1" + yf
                        + " GROUP BY dow ORDER BY dow";
        var dq = em.createNativeQuery(dowQ);
        if (year != null) dq.setParameter("year", year);
        if (personal) dq.setParameter("snowflake", snowflake);
        Map<String, Integer> eventsByDayOfWeek = new LinkedHashMap<>();
        ((List<Object[]>) dq.getResultList())
                .forEach(r -> eventsByDayOfWeek.put(dayNames[((Number) r[0]).intValue()], ((Number) r[1]).intValue()));

        // First event
        String firstQ = personal
                ? "SELECT e.id, e.name, e.date_time FROM event e "
                        + "JOIN attendance a ON a.event_id = e.id "
                        + "WHERE a.snowflake = :snowflake AND a.status = 'ACCEPTED' AND e.date_time IS NOT NULL" + yf
                        + " ORDER BY e.date_time ASC LIMIT 1"
                : "SELECT e.id, e.name, e.date_time FROM event e WHERE e.date_time IS NOT NULL" + yf
                        + " ORDER BY e.date_time ASC LIMIT 1";
        var feq = em.createNativeQuery(firstQ);
        if (year != null) feq.setParameter("year", year);
        if (personal) feq.setParameter("snowflake", snowflake);
        List<Object[]> firstRows = feq.getResultList();
        EventSummaryDto firstEvent = firstRows.isEmpty() ? null : toEventSummary(firstRows.get(0));

        // Last event
        String lastQ = personal
                ? "SELECT e.id, e.name, e.date_time FROM event e "
                        + "JOIN attendance a ON a.event_id = e.id "
                        + "WHERE a.snowflake = :snowflake AND a.status = 'ACCEPTED' AND e.date_time IS NOT NULL" + yf
                        + " ORDER BY e.date_time DESC LIMIT 1"
                : "SELECT e.id, e.name, e.date_time FROM event e WHERE e.date_time IS NOT NULL" + yf
                        + " ORDER BY e.date_time DESC LIMIT 1";
        var leq = em.createNativeQuery(lastQ);
        if (year != null) leq.setParameter("year", year);
        if (personal) leq.setParameter("snowflake", snowflake);
        List<Object[]> lastRows = leq.getResultList();
        EventSummaryDto lastEvent = lastRows.isEmpty() ? null : toEventSummary(lastRows.get(0));

        // Total +1 guests
        String plusQ = personal
                ? "SELECT COUNT(*) FROM attendance a "
                        + "JOIN event e ON a.event_id = e.id "
                        + "WHERE a.snowflake IS NULL AND a.status = 'ACCEPTED' AND a.owner_snowflake = :snowflake" + yf
                : "SELECT COUNT(*) FROM attendance a "
                        + "JOIN event e ON a.event_id = e.id "
                        + "WHERE a.snowflake IS NULL AND a.status = 'ACCEPTED'" + yf;
        var pog = em.createNativeQuery(plusQ);
        if (year != null) pog.setParameter("year", year);
        if (personal) pog.setParameter("snowflake", snowflake);
        int totalPlusOneGuests = ((Number) pog.getSingleResult()).intValue();

        return new RewindStatsDto(
                totalEvents,
                totalUniqueAttendees,
                totalRsvps,
                averageGroupSize,
                topCategories,
                topAttendees,
                topOrganizers,
                topSocialPairs,
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
