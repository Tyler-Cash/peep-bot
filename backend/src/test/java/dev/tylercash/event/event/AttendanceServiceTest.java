package dev.tylercash.event.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import dev.tylercash.event.db.repository.AttendanceRepository;
import dev.tylercash.event.event.model.AttendanceRecord;
import dev.tylercash.event.event.model.AttendanceStatus;
import dev.tylercash.event.event.model.AttendanceSummary;
import dev.tylercash.event.global.FeatureTogglesConfiguration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AttendanceServiceTest {

    private AttendanceRepository attendanceRepository;
    private FeatureTogglesConfiguration featureToggles;
    private AttendanceService service;

    @BeforeEach
    void setUp() {
        attendanceRepository = mock(AttendanceRepository.class);
        featureToggles = new FeatureTogglesConfiguration();
        service = new AttendanceService(attendanceRepository, featureToggles);
    }

    @Nested
    @DisplayName("getCurrentAttendance")
    class GetCurrentAttendance {

        @Test
        @DisplayName("partitions records by status, excluding REMOVED")
        void partitionsByStatus() {
            UUID eventId = UUID.randomUUID();
            AttendanceRecord accepted = new AttendanceRecord(eventId, "1", null, AttendanceStatus.ACCEPTED, null);
            AttendanceRecord declined = new AttendanceRecord(eventId, "2", null, AttendanceStatus.DECLINED, null);
            AttendanceRecord maybe = new AttendanceRecord(eventId, "3", null, AttendanceStatus.MAYBE, null);
            AttendanceRecord removed = new AttendanceRecord(eventId, "4", null, AttendanceStatus.REMOVED, null);

            when(attendanceRepository.findLatestPerAttendee(eventId))
                    .thenReturn(List.of(accepted, declined, maybe, removed));

            AttendanceSummary summary = service.getCurrentAttendance(eventId);

            assertThat(summary.accepted()).containsExactly(accepted);
            assertThat(summary.declined()).containsExactly(declined);
            assertThat(summary.maybe()).containsExactly(maybe);
        }
    }

    @Nested
    @DisplayName("flipAttendance (item 5: Discord RSVP toggle)")
    class FlipAttendance {

        @Test
        @DisplayName("records requested status when no prior attendance")
        void newAttendee_recordsRequestedStatus() {
            UUID eventId = UUID.randomUUID();
            when(attendanceRepository.findLatestPerAttendee(eventId)).thenReturn(List.of());

            service.flipAttendance(eventId, "user1", null, AttendanceStatus.ACCEPTED);

            ArgumentCaptor<AttendanceRecord> captor = ArgumentCaptor.forClass(AttendanceRecord.class);
            verify(attendanceRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(AttendanceStatus.ACCEPTED);
            assertThat(captor.getValue().getSnowflake()).isEqualTo("user1");
        }

        @Test
        @DisplayName("toggles to REMOVED when clicking same status")
        void sameStatus_togglesRemoved() {
            UUID eventId = UUID.randomUUID();
            AttendanceRecord existing = new AttendanceRecord(eventId, "user1", null, AttendanceStatus.ACCEPTED, null);
            when(attendanceRepository.findLatestPerAttendee(eventId)).thenReturn(List.of(existing));

            service.flipAttendance(eventId, "user1", null, AttendanceStatus.ACCEPTED);

            ArgumentCaptor<AttendanceRecord> captor = ArgumentCaptor.forClass(AttendanceRecord.class);
            verify(attendanceRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(AttendanceStatus.REMOVED);
        }

        @Test
        @DisplayName("switches to new status when clicking different status")
        void differentStatus_switchesStatus() {
            UUID eventId = UUID.randomUUID();
            AttendanceRecord existing = new AttendanceRecord(eventId, "user1", null, AttendanceStatus.ACCEPTED, null);
            when(attendanceRepository.findLatestPerAttendee(eventId)).thenReturn(List.of(existing));

            service.flipAttendance(eventId, "user1", null, AttendanceStatus.DECLINED);

            ArgumentCaptor<AttendanceRecord> captor = ArgumentCaptor.forClass(AttendanceRecord.class);
            verify(attendanceRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(AttendanceStatus.DECLINED);
        }
    }

    @Nested
    @DisplayName("+1 ownership (items 6, 10)")
    class PlusOneOwnership {

        @Test
        @DisplayName("+1 is recorded with owner_snowflake")
        void plusOneRecordedWithOwner() {
            UUID eventId = UUID.randomUUID();

            service.recordAttendance(eventId, null, "[+1] Dave", AttendanceStatus.ACCEPTED, "owner123");

            ArgumentCaptor<AttendanceRecord> captor = ArgumentCaptor.forClass(AttendanceRecord.class);
            verify(attendanceRepository).save(captor.capture());
            assertThat(captor.getValue().getSnowflake()).isNull();
            assertThat(captor.getValue().getName()).isEqualTo("[+1] Dave");
            assertThat(captor.getValue().getOwnerSnowflake()).isEqualTo("owner123");
            assertThat(captor.getValue().getStatus()).isEqualTo(AttendanceStatus.ACCEPTED);
        }

        @Test
        @DisplayName("isOwnerOfPlusOne returns true for matching owner")
        void isOwnerOfPlusOne_trueForOwner() {
            UUID eventId = UUID.randomUUID();
            AttendanceRecord plusOne =
                    new AttendanceRecord(eventId, null, "[+1] Dave", AttendanceStatus.ACCEPTED, "owner123");
            when(attendanceRepository.findLatestPerAttendee(eventId)).thenReturn(List.of(plusOne));

            assertThat(service.isOwnerOfPlusOne(eventId, "[+1] Dave", "owner123"))
                    .isTrue();
        }

        @Test
        @DisplayName("isOwnerOfPlusOne returns false for non-owner")
        void isOwnerOfPlusOne_falseForNonOwner() {
            UUID eventId = UUID.randomUUID();
            AttendanceRecord plusOne =
                    new AttendanceRecord(eventId, null, "[+1] Dave", AttendanceStatus.ACCEPTED, "owner123");
            when(attendanceRepository.findLatestPerAttendee(eventId)).thenReturn(List.of(plusOne));

            assertThat(service.isOwnerOfPlusOne(eventId, "[+1] Dave", "other456"))
                    .isFalse();
        }

        @Test
        @DisplayName("isOwnerOfPlusOne returns false for REMOVED +1")
        void isOwnerOfPlusOne_falseForRemovedPlusOne() {
            UUID eventId = UUID.randomUUID();
            AttendanceRecord plusOne =
                    new AttendanceRecord(eventId, null, "[+1] Dave", AttendanceStatus.REMOVED, "owner123");
            when(attendanceRepository.findLatestPerAttendee(eventId)).thenReturn(List.of(plusOne));

            assertThat(service.isOwnerOfPlusOne(eventId, "[+1] Dave", "owner123"))
                    .isFalse();
        }

        @Test
        @DisplayName("isOwnerOfPlusOne returns false for Discord users (snowflake present)")
        void isOwnerOfPlusOne_falseForDiscordUser() {
            UUID eventId = UUID.randomUUID();
            AttendanceRecord discordUser =
                    new AttendanceRecord(eventId, "user1", "UserName", AttendanceStatus.ACCEPTED, null);
            when(attendanceRepository.findLatestPerAttendee(eventId)).thenReturn(List.of(discordUser));

            assertThat(service.isOwnerOfPlusOne(eventId, "UserName", "user1")).isFalse();
        }
    }

    @Nested
    @DisplayName("+1 cascade on decline (item 12)")
    class CascadeOnDecline {

        @Test
        @DisplayName("declining removes owned +1s when toggle enabled")
        void decline_cascadeRemovesPlusOnes() {
            featureToggles.setRemovePlusOnesOnDecline(true);
            UUID eventId = UUID.randomUUID();

            AttendanceRecord owner = new AttendanceRecord(eventId, "owner1", null, AttendanceStatus.ACCEPTED, null);
            AttendanceRecord plusOne =
                    new AttendanceRecord(eventId, null, "[+1] Guest", AttendanceStatus.ACCEPTED, "owner1");
            when(attendanceRepository.findLatestPerAttendee(eventId)).thenReturn(List.of(owner, plusOne));

            service.flipAttendance(eventId, "owner1", null, AttendanceStatus.DECLINED);

            ArgumentCaptor<AttendanceRecord> captor = ArgumentCaptor.forClass(AttendanceRecord.class);
            verify(attendanceRepository, times(2)).save(captor.capture());

            List<AttendanceRecord> saved = captor.getAllValues();
            // First save: the owner's decline
            assertThat(saved.get(0).getSnowflake()).isEqualTo("owner1");
            assertThat(saved.get(0).getStatus()).isEqualTo(AttendanceStatus.DECLINED);
            // Second save: cascade removal of +1
            assertThat(saved.get(1).getName()).isEqualTo("[+1] Guest");
            assertThat(saved.get(1).getStatus()).isEqualTo(AttendanceStatus.REMOVED);
            assertThat(saved.get(1).getOwnerSnowflake()).isEqualTo("owner1");
        }

        @Test
        @DisplayName("removeAttendee also cascades owned +1s")
        void removeAttendee_cascadesPlusOnes() {
            featureToggles.setRemovePlusOnesOnDecline(true);
            UUID eventId = UUID.randomUUID();

            AttendanceRecord plusOne =
                    new AttendanceRecord(eventId, null, "[+1] Guest", AttendanceStatus.ACCEPTED, "owner1");
            when(attendanceRepository.findLatestPerAttendee(eventId)).thenReturn(List.of(plusOne));

            service.removeAttendee(eventId, "owner1", null);

            ArgumentCaptor<AttendanceRecord> captor = ArgumentCaptor.forClass(AttendanceRecord.class);
            verify(attendanceRepository, times(2)).save(captor.capture());

            List<AttendanceRecord> saved = captor.getAllValues();
            assertThat(saved.get(0).getStatus()).isEqualTo(AttendanceStatus.REMOVED);
            assertThat(saved.get(1).getName()).isEqualTo("[+1] Guest");
            assertThat(saved.get(1).getStatus()).isEqualTo(AttendanceStatus.REMOVED);
        }

        @Test
        @DisplayName("does not cascade for already-REMOVED +1s")
        void decline_doesNotCascadeAlreadyRemovedPlusOnes() {
            featureToggles.setRemovePlusOnesOnDecline(true);
            UUID eventId = UUID.randomUUID();

            AttendanceRecord owner = new AttendanceRecord(eventId, "owner1", null, AttendanceStatus.ACCEPTED, null);
            AttendanceRecord removedPlusOne =
                    new AttendanceRecord(eventId, null, "[+1] Gone", AttendanceStatus.REMOVED, "owner1");
            when(attendanceRepository.findLatestPerAttendee(eventId)).thenReturn(List.of(owner, removedPlusOne));

            service.flipAttendance(eventId, "owner1", null, AttendanceStatus.DECLINED);

            // Only one save: the owner's decline (no cascade for already-removed +1)
            verify(attendanceRepository, times(1)).save(any());
        }
    }

    @Nested
    @DisplayName("Feature toggle off (item 13)")
    class FeatureToggleOff {

        @Test
        @DisplayName("decline does NOT cascade +1 removal when toggle disabled")
        void decline_noCascadeWhenToggleOff() {
            featureToggles.setRemovePlusOnesOnDecline(false);
            UUID eventId = UUID.randomUUID();

            AttendanceRecord owner = new AttendanceRecord(eventId, "owner1", null, AttendanceStatus.ACCEPTED, null);
            AttendanceRecord plusOne =
                    new AttendanceRecord(eventId, null, "[+1] Guest", AttendanceStatus.ACCEPTED, "owner1");
            when(attendanceRepository.findLatestPerAttendee(eventId)).thenReturn(List.of(owner, plusOne));

            service.flipAttendance(eventId, "owner1", null, AttendanceStatus.DECLINED);

            // Only one save: the owner's decline (no cascade)
            verify(attendanceRepository, times(1)).save(any());
            ArgumentCaptor<AttendanceRecord> captor = ArgumentCaptor.forClass(AttendanceRecord.class);
            verify(attendanceRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(AttendanceStatus.DECLINED);
        }

        @Test
        @DisplayName("removeAttendee does NOT cascade when toggle disabled")
        void removeAttendee_noCascadeWhenToggleOff() {
            featureToggles.setRemovePlusOnesOnDecline(false);
            UUID eventId = UUID.randomUUID();

            service.removeAttendee(eventId, "owner1", null);

            // Only one save: the REMOVED record for the owner (no findLatestPerAttendee call)
            verify(attendanceRepository, times(1)).save(any());
            verify(attendanceRepository, never()).findLatestPerAttendee(any());
        }
    }
}
