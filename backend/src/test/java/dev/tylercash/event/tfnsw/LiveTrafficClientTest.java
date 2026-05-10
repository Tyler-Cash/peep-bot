package dev.tylercash.event.tfnsw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import dev.tylercash.event.tfnsw.TfnswNoteworthyFilter.TrafficEvent;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.Test;

class LiveTrafficClientTest {

    @Test
    void parsesMajorEventGeoJson() throws Exception {
        String json = Files.readString(Paths.get("src/test/resources/tfnsw/major-events-sample.json"));
        List<TrafficEvent> out = LiveTrafficClient.parseMajorEvents(json);
        assertThat(out).hasSize(1);
        TrafficEvent e = out.get(0);
        assertThat(e.id()).isEqualTo("evt-1");
        assertThat(e.kind()).isEqualTo(TrafficEvent.Kind.MAJOR_EVENT);
        assertThat(e.lat()).isCloseTo(-33.8688, offset(0.0001));
        assertThat(e.lng()).isCloseTo(151.2093, offset(0.0001));
        assertThat(e.headline()).isEqualTo("Vivid Sydney 2026");
        assertThat(e.url()).contains("evt-1");
    }

    @Test
    void parsesRoadClosureSkipsIncidents() throws Exception {
        String json = Files.readString(Paths.get("src/test/resources/tfnsw/hazards-sample.json"));
        List<TrafficEvent> out = LiveTrafficClient.parseHazards(json);
        // haz-2 is "incident" mainCategory — should be filtered out; only haz-1 remains.
        assertThat(out).hasSize(1);
        TrafficEvent e = out.get(0);
        assertThat(e.id()).isEqualTo("haz-1");
        assertThat(e.kind()).isEqualTo(TrafficEvent.Kind.ROAD_CLOSURE);
        assertThat(e.roadClass()).isEqualTo("arterial");
    }
}
