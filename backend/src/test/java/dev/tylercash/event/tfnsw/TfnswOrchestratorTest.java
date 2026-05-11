package dev.tylercash.event.tfnsw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.Guild;
import dev.tylercash.event.discord.GuildRepository;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.places.PlacesDetailsClient;
import dev.tylercash.event.places.PlacesDetailsClient.Coords;
import dev.tylercash.event.tfnsw.NoteworthyItem.Reason;
import dev.tylercash.event.tfnsw.NoteworthyItem.Source;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TfnswOrchestratorTest {

    private TfnswConfiguration cfg;
    private TfnswAlertsClient alerts;
    private LiveTrafficClient traffic;
    private TfnswNoteworthyFilter filter;
    private TfnswReportingService reporter;
    private TfnswEventSnapshotRepository snapshots;
    private EventRepository events;
    private GuildRepository guilds;
    private PlacesDetailsClient places;
    private GtfsStopsIndex stopsIndex;
    private TfnswOrchestrator sut;

    @BeforeEach
    void setUp() {
        cfg = new TfnswConfiguration();
        cfg.setApiKey("k"); // enabled
        alerts = mock(TfnswAlertsClient.class);
        traffic = mock(LiveTrafficClient.class);
        filter = mock(TfnswNoteworthyFilter.class);
        reporter = mock(TfnswReportingService.class);
        snapshots = mock(TfnswEventSnapshotRepository.class);
        events = mock(EventRepository.class);
        guilds = mock(GuildRepository.class);
        places = mock(PlacesDetailsClient.class);
        stopsIndex = mock(GtfsStopsIndex.class);
        when(stopsIndex.findNearest(anyDouble(), anyDouble(), anyDouble())).thenReturn(Optional.empty());
        sut = new TfnswOrchestrator(
                cfg, alerts, traffic, filter, reporter, snapshots, events, guilds, places, stopsIndex);
        when(alerts.fetchSydneyTrains()).thenReturn(List.of());
        when(alerts.fetchSydneyMetro()).thenReturn(List.of());
        when(alerts.fetchTripReplacements()).thenReturn(List.of());
        when(traffic.fetchMajorEvents()).thenReturn(List.of());
        when(traffic.fetchHazards()).thenReturn(List.of());
    }

    private Event event(UUID id, long guildId, String placeId) {
        Event e = new Event();
        e.setId(id);
        e.setServerId(guildId);
        e.setLocationPlaceId(placeId);
        e.setDateTime(ZonedDateTime.now(ZoneId.of("Australia/Sydney")).plusDays(7));
        e.setName("Evt");
        return e;
    }

    private Guild guild(long id, boolean tfnsw) {
        Guild g = new Guild();
        g.setGuildId(id);
        g.setTfnswEnabled(tfnsw);
        return g;
    }

    @Test
    void skipsWhenGuildFlagOff() {
        UUID id = UUID.randomUUID();
        when(events.findById(id)).thenReturn(Optional.of(event(id, 1L, "pid")));
        when(guilds.findById(1L)).thenReturn(Optional.of(guild(1L, false)));

        sut.process(id, false);

        verifyNoInteractions(alerts, traffic, reporter, places);
    }

    @Test
    void skipsWhenNoPlaceIdOnEvent() {
        UUID id = UUID.randomUUID();
        when(events.findById(id)).thenReturn(Optional.of(event(id, 1L, null)));
        when(guilds.findById(1L)).thenReturn(Optional.of(guild(1L, true)));

        sut.process(id, false);

        verifyNoInteractions(alerts, traffic, reporter, places);
    }

    @Test
    void resolvesCoordsFromPlacesAndPosts() {
        UUID id = UUID.randomUUID();
        Event e = event(id, 1L, "pid");
        when(events.findById(id)).thenReturn(Optional.of(e));
        when(guilds.findById(1L)).thenReturn(Optional.of(guild(1L, true)));
        when(places.fetchCoords("pid")).thenReturn(Optional.of(new Coords(-33.0, 151.0)));
        when(filter.filter(any(), any(), eq(-33.0), eq(151.0), any(), any()))
                .thenReturn(List.of(new NoteworthyItem(Source.RAIL_METRO, "a", "x", "y", "u", Reason.NEAREST_STATION)));

        sut.process(id, false);

        verify(reporter).post(eq(e), anyList(), eq(false));
        verify(snapshots).save(any(TfnswEventSnapshot.class));
        // coords should be cached on the event
        assertThat(e.getLocationLat()).isEqualTo(-33.0);
        assertThat(e.getLocationLng()).isEqualTo(151.0);
    }

    @Test
    void usesCachedCoordsWithoutCallingPlaces() {
        UUID id = UUID.randomUUID();
        Event e = event(id, 1L, "pid");
        e.setLocationLat(-33.5);
        e.setLocationLng(151.5);
        when(events.findById(id)).thenReturn(Optional.of(e));
        when(guilds.findById(1L)).thenReturn(Optional.of(guild(1L, true)));
        when(filter.filter(any(), any(), anyDouble(), anyDouble(), any(), any()))
                .thenReturn(List.of());

        sut.process(id, false);

        verifyNoInteractions(places);
    }

    @Test
    void passesResolvedNearestStationIdIntoFilter() {
        UUID id = UUID.randomUUID();
        Event e = event(id, 1L, "pid");
        e.setLocationLat(-33.0);
        e.setLocationLng(151.0);
        when(events.findById(id)).thenReturn(Optional.of(e));
        when(guilds.findById(1L)).thenReturn(Optional.of(guild(1L, true)));
        when(stopsIndex.findNearest(eq(-33.0), eq(151.0), anyDouble()))
                .thenReturn(Optional.of(new GtfsStopsIndex.Stop("200060", "Central", -33.0, 151.0)));
        when(filter.filter(any(), any(), anyDouble(), anyDouble(), eq("200060"), any()))
                .thenReturn(List.of());

        sut.process(id, false);

        verify(filter).filter(any(), any(), anyDouble(), anyDouble(), eq("200060"), any());
    }

    @Test
    void weekBeforeSuppressesWhenHashUnchanged() {
        UUID id = UUID.randomUUID();
        Event e = event(id, 1L, "pid");
        e.setLocationLat(-33.0);
        e.setLocationLng(151.0);
        when(events.findById(id)).thenReturn(Optional.of(e));
        when(guilds.findById(1L)).thenReturn(Optional.of(guild(1L, true)));
        var item = new NoteworthyItem(Source.RAIL_METRO, "a", "x", "y", "u", Reason.NEAREST_STATION);
        when(filter.filter(any(), any(), anyDouble(), anyDouble(), any(), any()))
                .thenReturn(List.of(item));

        TfnswEventSnapshot prev = new TfnswEventSnapshot();
        prev.setEventId(id);
        prev.setAlertIdsHash(TfnswOrchestrator.hash(List.of(item)));
        prev.setLastPostedAt(java.time.Instant.now());
        when(snapshots.findById(id)).thenReturn(Optional.of(prev));

        sut.process(id, true);

        verifyNoInteractions(reporter);
    }

    @Test
    void disabledConfigSkipsEverything() {
        cfg.setApiKey(""); // disabled
        UUID id = UUID.randomUUID();

        sut.process(id, false);

        verifyNoInteractions(events, guilds, alerts, traffic, reporter);
    }
}
