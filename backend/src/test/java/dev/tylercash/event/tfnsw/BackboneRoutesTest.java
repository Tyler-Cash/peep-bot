package dev.tylercash.event.tfnsw;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class BackboneRoutesTest {

    @Test
    void metroNorthwestMatches() {
        assertThat(BackboneRoutes.touches(Set.of("SMNW_M1"))).isTrue();
    }

    @Test
    void sydneyTrainsLineMatches() {
        assertThat(BackboneRoutes.touches(Set.of("T1"))).isTrue();
        assertThat(BackboneRoutes.touches(Set.of("APS_1a"))).isTrue();
        assertThat(BackboneRoutes.touches(Set.of("BMT_1"))).isTrue();
        assertThat(BackboneRoutes.touches(Set.of("CCN_2b"))).isTrue();
    }

    @Test
    void unknownRouteDoesNotMatch() {
        assertThat(BackboneRoutes.touches(Set.of("LightRail_L1"))).isFalse();
        assertThat(BackboneRoutes.touches(Set.of("BUS_400"))).isFalse();
    }

    @Test
    void tripLevelIdDoesNotMatch() {
        assertThat(BackboneRoutes.touches(Set.of("4T.C.2301"))).isFalse();
        assertThat(BackboneRoutes.touches(Set.of("4T.T.NP23"))).isFalse();
    }

    @Test
    void mixedSetMatchesIfAnyBackboneIsPresent() {
        assertThat(BackboneRoutes.touches(Set.of("4T.C.2301", "SMNW_M1"))).isTrue();
    }

    @Test
    void emptySetDoesNotMatch() {
        assertThat(BackboneRoutes.touches(Set.of())).isFalse();
    }
}
