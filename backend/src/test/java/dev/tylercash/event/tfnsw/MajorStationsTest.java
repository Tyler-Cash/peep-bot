package dev.tylercash.event.tfnsw;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MajorStationsTest {
    @Test
    void containsCentralWynyardTownHall() {
        assertThat(MajorStations.STOP_IDS).contains("CENTRAL", "WYNYARD", "TOWN_HALL");
    }

    @Test
    void containsCuratedSetSize() {
        // Locked size — additions are intentional, not accidental.
        // Update both this number and the set together.
        assertThat(MajorStations.STOP_IDS).hasSize(MajorStations.EXPECTED_SIZE);
    }
}
