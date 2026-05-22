package dev.tylercash.event.tfnsw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.tylercash.event.tfnsw.GtfsStopsIndex.Stop;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

class GtfsStopsIndexTest {

    private static final String STOPS_TXT =
            """
            stop_id,stop_code,stop_name,stop_desc,stop_lat,stop_lon,zone_id,stop_url,location_type,parent_station,wheelchair_boarding,platform_code
            200060,,Central Station,,-33.8832,151.2065,,,1,,1,
            200070,,Town Hall Station,,-33.8731,151.2069,,,1,,1,
            2000061,,Central Station Platform 1,,-33.8832,151.2065,,,0,200060,1,1
            999999,,Some Suburb Bus Stop,,-34.0,151.0,,,0,,1,
            """;

    @Test
    void parsesParentStationsAndIgnoresPlatformsAndOtherTypes() {
        List<Stop> stops = GtfsStopsIndex.parseStopsCsv(STOPS_TXT);
        assertThat(stops).extracting(Stop::stopId).containsExactlyInAnyOrder("200060", "200070");
    }

    @Test
    void parsesQuotedFieldsAsEmittedByLiveTfnswFeed() {
        // Live TfNSW feed quotes every field including the header — caused empty index in prod.
        String quoted =
                """
                "stop_id","stop_code","stop_name","stop_desc","stop_lat","stop_lon","zone_id","stop_url","location_type","parent_station","wheelchair_boarding","platform_code"
                "200060","","Central Station","","-33.8832","151.2065","","","1","","1",""
                "2000061","","Central Station Platform 1","","-33.8832","151.2065","","","0","200060","1","1"
                """;
        List<Stop> stops = GtfsStopsIndex.parseStopsCsv(quoted);
        assertThat(stops).extracting(Stop::stopId).containsExactly("200060");
        assertThat(stops).extracting(Stop::name).containsExactly("Central Station");
    }

    @Test
    void parsesStopsFromZip() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry("agency.txt"));
            zos.write("ignored".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("stops.txt"));
            zos.write(STOPS_TXT.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        List<Stop> stops = GtfsStopsIndex.parseStopsFromZip(bos.toByteArray());
        assertThat(stops).hasSize(2);
    }

    @Test
    void findNearestReturnsClosestWithinRadius() {
        GtfsStopsIndex idx = bareIndex();
        idx.setStopsForTest(
                List.of(new Stop("A", "Near", -33.870, 151.207), new Stop("B", "Far", -34.000, 151.000)),
                java.util.Set.of());
        assertThat(idx.findNearest(-33.870, 151.207, 0.5)).map(Stop::stopId).contains("A");
    }

    @Test
    void findNearestReturnsEmptyWhenOutsideRadius() {
        GtfsStopsIndex idx = bareIndex();
        idx.setStopsForTest(List.of(new Stop("A", "Far", -34.0, 151.0)), java.util.Set.of());
        assertThat(idx.findNearest(-33.0, 151.0, 1.0)).isEmpty();
    }

    @Test
    void refreshSkipsWhenApiKeyMissing() {
        TfnswConfiguration cfg = new TfnswConfiguration(); // apiKey blank → disabled
        RestClient client = mock(RestClient.class);
        GtfsStopsIndex idx = new GtfsStopsIndex(cfg, client);

        idx.refresh();

        assertThat(idx.majorStopIds()).isEmpty();
        // Critically: no HTTP call attempted.
        org.mockito.Mockito.verifyNoInteractions(client);
    }

    @Test
    void refreshResolvesMajorStationIdsFromFeedByName() throws Exception {
        TfnswConfiguration cfg = new TfnswConfiguration();
        cfg.setApiKey("k");

        byte[] trainsZip = zipWithStopsTxt(STOPS_TXT);
        byte[] metroZip = zipWithStopsTxt("stop_id,stop_name,stop_lat,stop_lon,location_type\n");

        RestClient client = mock(RestClient.class);
        RestClient.RequestHeadersUriSpec uriSpec = mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.RequestHeadersSpec<?> headersSpec1 = mock(RestClient.RequestHeadersSpec.class);
        RestClient.RequestHeadersSpec<?> headersSpec2 = mock(RestClient.RequestHeadersSpec.class);
        RestClient.ResponseSpec respSpec1 = mock(RestClient.ResponseSpec.class);
        RestClient.ResponseSpec respSpec2 = mock(RestClient.ResponseSpec.class);
        when(client.get()).thenReturn(uriSpec);
        when(uriSpec.uri(cfg.getSydneyTrainsSchedulePath())).thenAnswer(inv -> headersSpec1);
        when(uriSpec.uri(cfg.getSydneyMetroSchedulePath())).thenAnswer(inv -> headersSpec2);
        when(headersSpec1.retrieve()).thenReturn(respSpec1);
        when(headersSpec2.retrieve()).thenReturn(respSpec2);
        when(respSpec1.body(any(Class.class))).thenReturn(trainsZip);
        when(respSpec2.body(any(Class.class))).thenReturn(metroZip);

        GtfsStopsIndex idx = new GtfsStopsIndex(cfg, client);
        idx.refresh();

        assertThat(idx.majorStopIds()).containsExactlyInAnyOrder("200060", "200070");
    }

    private static byte[] zipWithStopsTxt(String stopsTxt) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry("stops.txt"));
            zos.write(stopsTxt.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return bos.toByteArray();
    }

    private static GtfsStopsIndex bareIndex() {
        GtfsStopsIndex idx = new GtfsStopsIndex(new TfnswConfiguration(), null);
        // Avoid invoking @PostConstruct via ApplicationReadyEvent; tests drive state directly.
        ReflectionTestUtils.invokeMethod(idx, "setStopsForTest", List.of(), java.util.Set.of());
        return idx;
    }
}
