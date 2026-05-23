package dev.tylercash.event.tfnsw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import java.util.Set;
import java.util.UUID;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
    private LockProvider lockProvider;
    private TfnswOrchestrator sut;

    @BeforeEach
    void setUp() {
        cfg = new TfnswConfiguration();
        cfg.setApiKey("k");
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
        lockProvider = mock(LockProvider.class);
        // Default to "lock acquired" so existing tests don't have to think about it.
        when(lockProvider.lock(any(LockConfiguration.class))).thenReturn(Optional.of(mock(SimpleLock.class)));
        sut = new TfnswOrchestrator(
                cfg, alerts, traffic, filter, reporter, snapshots, events, guilds, places, stopsIndex, lockProvider);
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
        e.setChannelId(424242L);
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

    private NoteworthyItem item(String id) {
        return new NoteworthyItem(
                Source.RAIL_METRO,
                id,
                "h",
                "d",
                "u",
                Reason.CITYWIDE_LINE,
                Set.of("SMNW_M1"),
                TfnswNoteworthyFilter.RailAlert.Cause.UNKNOWN,
                java.util.List.of(),
                java.util.List.of());
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
    void firstRunPersistsMessageIdAndPostedAlertIds() {
        UUID id = UUID.randomUUID();
        Event e = event(id, 1L, "pid");
        e.setLocationLat(-33.0);
        e.setLocationLng(151.0);
        when(events.findById(id)).thenReturn(Optional.of(e));
        when(guilds.findById(1L)).thenReturn(Optional.of(guild(1L, true)));
        when(filter.filter(any(), any(), anyDouble(), anyDouble(), any(), any()))
                .thenReturn(List.of(item("metro-1")));
        when(reporter.post(eq(e), any())).thenReturn(555L);

        sut.process(id, false);

        ArgumentCaptor<TfnswEventSnapshot> snap = ArgumentCaptor.forClass(TfnswEventSnapshot.class);
        verify(snapshots).save(snap.capture());
        assertThat(snap.getValue().getOriginalMessageId()).isEqualTo(555L);
        assertThat(snap.getValue().getPostedAlertIds()).isEqualTo("metro-1");
    }

    @Test
    void recheckWithNoNewItemsDoesNotPost() {
        UUID id = UUID.randomUUID();
        Event e = event(id, 1L, "pid");
        e.setLocationLat(-33.0);
        e.setLocationLng(151.0);
        when(events.findById(id)).thenReturn(Optional.of(e));
        when(guilds.findById(1L)).thenReturn(Optional.of(guild(1L, true)));
        when(filter.filter(any(), any(), anyDouble(), anyDouble(), any(), any()))
                .thenReturn(List.of(item("metro-1"), item("trains-1")));

        TfnswEventSnapshot prev = new TfnswEventSnapshot();
        prev.setEventId(id);
        prev.setAlertIdsHash("anything");
        prev.setOriginalMessageId(555L);
        prev.setPostedAlertIds("metro-1\ntrains-1");
        when(snapshots.findById(id)).thenReturn(Optional.of(prev));

        sut.process(id, true);

        verify(reporter, never()).post(any(), any());
        verify(reporter, never()).postUpdate(any(), anyLong(), any());
    }

    @Test
    void recheckWithNewItemsRepliesToOriginal() {
        UUID id = UUID.randomUUID();
        Event e = event(id, 1L, "pid");
        e.setLocationLat(-33.0);
        e.setLocationLng(151.0);
        when(events.findById(id)).thenReturn(Optional.of(e));
        when(guilds.findById(1L)).thenReturn(Optional.of(guild(1L, true)));
        when(filter.filter(any(), any(), anyDouble(), anyDouble(), any(), any()))
                .thenReturn(List.of(item("metro-1"), item("trains-2")));

        TfnswEventSnapshot prev = new TfnswEventSnapshot();
        prev.setEventId(id);
        prev.setAlertIdsHash("anything");
        prev.setOriginalMessageId(555L);
        prev.setPostedAlertIds("metro-1");
        when(snapshots.findById(id)).thenReturn(Optional.of(prev));
        when(reporter.postUpdate(eq(e), eq(555L), any())).thenReturn(true);

        sut.process(id, true);

        ArgumentCaptor<List<NoteworthyItem>> delta = ArgumentCaptor.forClass(List.class);
        verify(reporter).postUpdate(eq(e), eq(555L), delta.capture());
        assertThat(delta.getValue()).extracting(NoteworthyItem::id).containsExactly("trains-2");

        ArgumentCaptor<TfnswEventSnapshot> snap = ArgumentCaptor.forClass(TfnswEventSnapshot.class);
        verify(snapshots).save(snap.capture());
        assertThat(snap.getValue().getPostedAlertIds()).isEqualTo("metro-1\ntrains-2");
    }

    @Test
    void recheckFallsBackToFreshPostWhenReplyTargetMissing() {
        UUID id = UUID.randomUUID();
        Event e = event(id, 1L, "pid");
        e.setLocationLat(-33.0);
        e.setLocationLng(151.0);
        when(events.findById(id)).thenReturn(Optional.of(e));
        when(guilds.findById(1L)).thenReturn(Optional.of(guild(1L, true)));
        when(filter.filter(any(), any(), anyDouble(), anyDouble(), any(), any()))
                .thenReturn(List.of(item("metro-1"), item("trains-2")));

        TfnswEventSnapshot prev = new TfnswEventSnapshot();
        prev.setEventId(id);
        prev.setAlertIdsHash("anything");
        prev.setOriginalMessageId(555L);
        prev.setPostedAlertIds("metro-1");
        when(snapshots.findById(id)).thenReturn(Optional.of(prev));
        when(reporter.postUpdate(eq(e), eq(555L), any())).thenReturn(false);
        when(reporter.post(eq(e), any())).thenReturn(777L);

        sut.process(id, true);

        verify(reporter).post(eq(e), any());

        ArgumentCaptor<TfnswEventSnapshot> snap = ArgumentCaptor.forClass(TfnswEventSnapshot.class);
        verify(snapshots).save(snap.capture());
        assertThat(snap.getValue().getOriginalMessageId()).isEqualTo(777L);
        assertThat(snap.getValue().getPostedAlertIds()).isEqualTo("metro-1\ntrains-2");
    }

    @Test
    void resolvesCoordsFromPlaces() {
        UUID id = UUID.randomUUID();
        Event e = event(id, 1L, "pid");
        when(events.findById(id)).thenReturn(Optional.of(e));
        when(guilds.findById(1L)).thenReturn(Optional.of(guild(1L, true)));
        when(places.fetchCoords("pid")).thenReturn(Optional.of(new Coords(-33.0, 151.0)));
        when(filter.filter(any(), any(), eq(-33.0), eq(151.0), any(), any())).thenReturn(List.of());

        sut.process(id, false);

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
    void retriedFirstRunDoesNotDoublePostWhenSnapshotAlreadyHasMessageId() {
        UUID id = UUID.randomUUID();
        Event e = event(id, 1L, "pid");
        e.setLocationLat(-33.0);
        e.setLocationLng(151.0);
        when(events.findById(id)).thenReturn(Optional.of(e));
        when(guilds.findById(1L)).thenReturn(Optional.of(guild(1L, true)));
        when(filter.filter(any(), any(), anyDouble(), anyDouble(), any(), any()))
                .thenReturn(List.of(item("metro-1")));

        TfnswEventSnapshot prev = new TfnswEventSnapshot();
        prev.setEventId(id);
        prev.setOriginalMessageId(555L);
        prev.setPostedAlertIds("metro-1");
        when(snapshots.findById(id)).thenReturn(Optional.of(prev));

        // EventCreated listener retried by the outbox after we already posted —
        // must not call reporter.post again, and must not synthesise an update.
        sut.process(id, false);

        verify(reporter, never()).post(any(), any());
        verify(reporter, never()).postUpdate(any(), anyLong(), any());
    }

    @Test
    void disabledConfigSkipsEverything() {
        cfg.setApiKey("");
        UUID id = UUID.randomUUID();

        sut.process(id, false);

        verifyNoInteractions(events, guilds, alerts, traffic, reporter);
    }
}
