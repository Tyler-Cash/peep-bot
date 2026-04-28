package dev.tylercash.event.event.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EventDetailDtoTest {

    private static Event buildEvent() {
        return new Event(0L, 0L, 0L, "Test Event", "creator", ZonedDateTime.now(), "description");
    }

    @Test
    void emptyAttendeeSetsMappedToEmptyLists() {
        Event event = buildEvent();

        EventDetailDto dto = new EventDetailDto(event, false);

        assertThat(dto.getAccepted()).isEmpty();
        assertThat(dto.getDeclined()).isEmpty();
        assertThat(dto.getMaybe()).isEmpty();
    }

    @Test
    void acceptedAttendeesAreMappedCorrectly() {
        Event event = buildEvent();
        event.getAccepted().add(Attendee.createDiscordAttendee("123", "Alice"));

        EventDetailDto dto = new EventDetailDto(event, false);

        assertThat(dto.getAccepted()).hasSize(1);
        AttendeeDto attendeeDto = dto.getAccepted().get(0);
        assertThat(attendeeDto.getSnowflake()).isEqualTo("123");
        assertThat(attendeeDto.getName()).isEqualTo("Alice");
        assertThat(attendeeDto.getInstant()).isNotNull();
    }

    @Test
    void attendeesSortedByInstantAscending() throws InterruptedException {
        Event event = buildEvent();
        Attendee first = Attendee.createDiscordAttendee("aaa", "First");
        Thread.sleep(5);
        Attendee second = Attendee.createDiscordAttendee("bbb", "Second");
        event.getAccepted().add(second);
        event.getAccepted().add(first);

        EventDetailDto dto = new EventDetailDto(event, false);

        List<AttendeeDto> accepted = dto.getAccepted();
        assertThat(accepted).hasSize(2);
        assertThat(accepted.get(0).getSnowflake()).isEqualTo("aaa");
        assertThat(accepted.get(1).getSnowflake()).isEqualTo("bbb");
    }

    @Test
    void allThreeListsPopulatedFromCorrectSets() {
        Event event = buildEvent();
        event.getAccepted().add(Attendee.createDiscordAttendee("1", "Accepted Person"));
        event.getDeclined().add(Attendee.createDiscordAttendee("2", "Declined Person"));
        event.getMaybe().add(Attendee.createDiscordAttendee("3", "Maybe Person"));

        EventDetailDto dto = new EventDetailDto(event, false);

        assertThat(dto.getAccepted()).hasSize(1);
        assertThat(dto.getAccepted().get(0).getName()).isEqualTo("Accepted Person");
        assertThat(dto.getDeclined()).hasSize(1);
        assertThat(dto.getDeclined().get(0).getName()).isEqualTo("Declined Person");
        assertThat(dto.getMaybe()).hasSize(1);
        assertThat(dto.getMaybe().get(0).getName()).isEqualTo("Maybe Person");
    }

    @Test
    void guestAttendeeWithNullSnowflakeMappedWithPlusOnePrefix() {
        Event event = buildEvent();
        event.getAccepted().add(Attendee.createDiscordAttendee(null, "Guest"));

        EventDetailDto dto = new EventDetailDto(event, false);

        assertThat(dto.getAccepted()).hasSize(1);
        assertThat(dto.getAccepted().get(0).getSnowflake()).isNull();
        assertThat(dto.getAccepted().get(0).getName()).isEqualTo("[+1] Guest");
    }

    @Test
    void multipleAttendeesAcrossAllListsSortedIndependently() throws InterruptedException {
        Event event = buildEvent();
        Attendee a1 = Attendee.createDiscordAttendee("a1", "AccFirst");
        Thread.sleep(5);
        Attendee a2 = Attendee.createDiscordAttendee("a2", "AccSecond");
        event.getAccepted().add(a2);
        event.getAccepted().add(a1);

        Attendee m1 = Attendee.createDiscordAttendee("m1", "MaybeFirst");
        Thread.sleep(5);
        Attendee m2 = Attendee.createDiscordAttendee("m2", "MaybeSecond");
        event.getMaybe().add(m2);
        event.getMaybe().add(m1);

        EventDetailDto dto = new EventDetailDto(event, false);

        assertThat(dto.getAccepted().get(0).getSnowflake()).isEqualTo("a1");
        assertThat(dto.getAccepted().get(1).getSnowflake()).isEqualTo("a2");
        assertThat(dto.getMaybe().get(0).getSnowflake()).isEqualTo("m1");
        assertThat(dto.getMaybe().get(1).getSnowflake()).isEqualTo("m2");
    }

    private static AttendanceRecord buildRecord(String snowflake, String name) {
        return new AttendanceRecord(
                null, java.util.UUID.randomUUID(), snowflake, name, AttendanceStatus.ACCEPTED, null, Instant.now());
    }

    @Test
    void fiveArgConstructor_hostResolvedFromNameMap() {
        Event event = buildEvent();
        event.setCreator("creator-snowflake");
        AttendanceSummary summary = new AttendanceSummary(List.of(), List.of(), List.of());
        Map<String, String> displayNames = Map.of("creator-snowflake", "Host Display Name");
        Map<String, String> usernames = Map.of("creator-snowflake", "host_username");

        EventDetailDto dto = new EventDetailDto(event, false, summary, displayNames, usernames);

        assertThat(dto.getHost()).isEqualTo("Host Display Name");
        assertThat(dto.getHostUsername()).isEqualTo("host_username");
    }

    @Test
    void fiveArgConstructor_hostAvatarUrlUsesCreatorSnowflake() {
        Event event = buildEvent();
        event.setCreator("creator-snowflake");
        AttendanceSummary summary = new AttendanceSummary(List.of(), List.of(), List.of());
        Map<String, String> displayNames = Map.of("creator-snowflake", "Host Display Name");
        Map<String, String> usernames = Map.of("creator-snowflake", "host_username");

        EventDetailDto dto = new EventDetailDto(event, false, summary, displayNames, usernames);

        assertThat(dto.getHostAvatarUrl()).isEqualTo("/api/avatar/creator-snowflake");
    }

    @Test
    void fiveArgConstructor_hostFallsBackToSnowflakeWhenNotInMap() {
        Event event = buildEvent();
        event.setCreator("creator-snowflake");
        AttendanceSummary summary = new AttendanceSummary(List.of(), List.of(), List.of());

        EventDetailDto dto = new EventDetailDto(event, false, summary, Map.of(), Map.of());

        assertThat(dto.getHost()).isEqualTo("creator-snowflake");
        assertThat(dto.getHostAvatarUrl()).isEqualTo("/api/avatar/creator-snowflake");
    }

    @Test
    void fiveArgConstructor_attendanceRecordsPopulatedFromSummary() {
        Event event = buildEvent();
        event.setCreator("creator-snowflake");
        AttendanceRecord accepted = buildRecord("user-1", "Alice");
        AttendanceRecord declined = buildRecord("user-2", "Bob");
        AttendanceRecord maybe = buildRecord("user-3", "Charlie");
        AttendanceSummary summary = new AttendanceSummary(List.of(accepted), List.of(declined), List.of(maybe));
        Map<String, String> displayNames = Map.of(
                "creator-snowflake", "Host",
                "user-1", "Alice Resolved",
                "user-2", "Bob Resolved",
                "user-3", "Charlie Resolved");
        Map<String, String> usernames = Map.of(
                "creator-snowflake", "host_user",
                "user-1", "alice_user",
                "user-2", "bob_user",
                "user-3", "charlie_user");

        EventDetailDto dto = new EventDetailDto(event, false, summary, displayNames, usernames);

        assertThat(dto.getAccepted()).hasSize(1);
        assertThat(dto.getAccepted().get(0).getName()).isEqualTo("Alice Resolved");
        assertThat(dto.getAccepted().get(0).getUsername()).isEqualTo("alice_user");
        assertThat(dto.getDeclined()).hasSize(1);
        assertThat(dto.getDeclined().get(0).getName()).isEqualTo("Bob Resolved");
        assertThat(dto.getDeclined().get(0).getUsername()).isEqualTo("bob_user");
        assertThat(dto.getMaybe()).hasSize(1);
        assertThat(dto.getMaybe().get(0).getName()).isEqualTo("Charlie Resolved");
        assertThat(dto.getMaybe().get(0).getUsername()).isEqualTo("charlie_user");
    }
}
