package dev.tylercash.event.tfnsw;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class LineNamesTest {

    @Test
    void looksUpMetroNorthwestByLineVariant() {
        assertThat(LineNames.lookup("SMNW_M1")).contains("Sydney Metro Northwest");
        assertThat(LineNames.lookup("SMNW")).contains("Sydney Metro Northwest");
    }

    @Test
    void looksUpSydneyTrainsLines() {
        assertThat(LineNames.lookup("T1")).contains("T1 Western");
        assertThat(LineNames.lookup("APS_1a")).contains("Airport & South Line");
        assertThat(LineNames.lookup("BMT_1")).contains("Blue Mountains Line");
    }

    @Test
    void returnsEmptyForUnknownRouteId() {
        assertThat(LineNames.lookup("LightRail_L1")).isEmpty();
        assertThat(LineNames.lookup("4T.C.2301")).isEmpty();
    }

    @Test
    void distinctNamesDeduplicatesVariantsOfSameLine() {
        Set<String> routeIds = Set.of("APS_1a", "APS_1b", "APS_2a", "APS_2b");
        assertThat(LineNames.distinctNames(routeIds)).containsExactly("Airport & South Line");
    }

    @Test
    void distinctNamesPreservesSortedOrder() {
        Set<String> routeIds = Set.of("T1", "SMNW_M1", "BMT_1");
        assertThat(LineNames.distinctNames(routeIds))
                .containsExactly("Blue Mountains Line", "Sydney Metro Northwest", "T1 Western");
    }

    @Test
    void distinctNamesDropsUnknownRouteIdsSilently() {
        Set<String> routeIds = Set.of("SMNW_M1", "4T.C.2301", "LightRail_L1");
        assertThat(LineNames.distinctNames(routeIds)).containsExactly("Sydney Metro Northwest");
    }
}
