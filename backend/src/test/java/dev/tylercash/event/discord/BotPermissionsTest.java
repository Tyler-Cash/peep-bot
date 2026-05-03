package dev.tylercash.event.discord;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BotPermissionsTest {

    @Test
    void permissionsIntegerMatchesExpected() {
        assertThat(BotPermissions.REQUIRED).isEqualTo(2251800082598928L);
        assertThat(BotPermissions.REQUIRED & 0x0008_0000_0000_0000L).isNotZero();
    }

    @Test
    void everyEnumEntryHasNonBlankMetadata() {
        for (BotPermission p : BotPermission.values()) {
            assertThat(p.bit()).isNotZero();
            assertThat(p.displayName()).isNotBlank();
            assertThat(p.reason()).isNotBlank();
        }
    }
}
