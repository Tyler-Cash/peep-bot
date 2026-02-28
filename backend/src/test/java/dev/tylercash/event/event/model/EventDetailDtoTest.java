package dev.tylercash.event.event.model;

import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EventDetailDtoTest {

    private static Event buildEvent() {
        return new Event(0L, 0L, 0L, "Test Event", "creator", ZonedDateTime.now(), "description");
    }

    @Test
    void emptyAttendeeSetsMappedToEmptyLists() {
        Event event = buildEvent();

        EventDetailDto dto = new EventDetailDto(event);

        assertThat(dto.getAccepted()).isEmpty();
        assertThat(dto.getDeclined()).isEmpty();
        assertThat(dto.getMaybe()).isEmpty();
    }

    @Test
    void acceptedAttendeesAreMappedCorrectly() {
        Event event = buildEvent();
        event.getAccepted().add(Attendee.createDiscordAttendee("123", "Alice"));

        EventDetailDto dto = new EventDetailDto(event);

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
        // Add in reverse order to ensure sorting is applied
        event.getAccepted().add(second);
        event.getAccepted().add(first);

        EventDetailDto dto = new EventDetailDto(event);

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

        EventDetailDto dto = new EventDetailDto(event);

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

        EventDetailDto dto = new EventDetailDto(event);

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

        EventDetailDto dto = new EventDetailDto(event);

        assertThat(dto.getAccepted().get(0).getSnowflake()).isEqualTo("a1");
        assertThat(dto.getAccepted().get(1).getSnowflake()).isEqualTo("a2");
        assertThat(dto.getMaybe().get(0).getSnowflake()).isEqualTo("m1");
        assertThat(dto.getMaybe().get(1).getSnowflake()).isEqualTo("m2");
    }
}
