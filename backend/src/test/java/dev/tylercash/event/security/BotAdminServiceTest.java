package dev.tylercash.event.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class BotAdminServiceTest {

    private BotAdminService service(List<String> admins) {
        BotAdminProperties props = new BotAdminProperties();
        props.setBotAdmins(admins);
        return new BotAdminService(props);
    }

    @Test
    void snowflakeInList_returnsTrue() {
        BotAdminService svc = service(List.of("111", "222"));
        assertThat(svc.isBotAdmin("111")).isTrue();
    }

    @Test
    void snowflakeNotInList_returnsFalse() {
        BotAdminService svc = service(List.of("111", "222"));
        assertThat(svc.isBotAdmin("999")).isFalse();
    }

    @Test
    void nullSnowflake_returnsFalse() {
        BotAdminService svc = service(List.of("111"));
        assertThat(svc.isBotAdmin(null)).isFalse();
    }
}
