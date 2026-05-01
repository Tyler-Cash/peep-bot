package dev.tylercash.event.discord;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BotPermissionsTest {

    @Test
    void permissionsIntegerMatchesExpected() {
        assertThat(BotPermissions.REQUIRED).isEqualTo(268954640L);
    }
}
