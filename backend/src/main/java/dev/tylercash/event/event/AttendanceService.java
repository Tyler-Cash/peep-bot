package dev.tylercash.event.event;

import dev.tylercash.event.db.repository.AttendanceRepository;
import dev.tylercash.event.event.model.AttendanceRecord;
import dev.tylercash.event.event.model.AttendanceStatus;
import dev.tylercash.event.event.model.AttendanceSummary;
import dev.tylercash.event.global.FeatureTogglesConfiguration;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

@Log4j2
@Service
@RequiredArgsConstructor
public class AttendanceService {
    private final AttendanceRepository attendanceRepository;
    private final FeatureTogglesConfiguration featureToggles;

    public void recordAttendance(
            UUID eventId, String snowflake, String name, AttendanceStatus status, String ownerSnowflake) {
        attendanceRepository.save(new AttendanceRecord(eventId, snowflake, name, status, ownerSnowflake));
    }

    public AttendanceSummary getCurrentAttendance(UUID eventId) {
        List<AttendanceRecord> latest = attendanceRepository.findLatestPerAttendee(eventId);
        List<AttendanceRecord> accepted = latest.stream()
                .filter(r -> r.getStatus() == AttendanceStatus.ACCEPTED)
                .toList();
        List<AttendanceRecord> declined = latest.stream()
                .filter(r -> r.getStatus() == AttendanceStatus.DECLINED)
                .toList();
        List<AttendanceRecord> maybe = latest.stream()
                .filter(r -> r.getStatus() == AttendanceStatus.MAYBE)
                .toList();
        return new AttendanceSummary(accepted, declined, maybe);
    }

    public void flipAttendance(UUID eventId, String snowflake, String name, AttendanceStatus requestedStatus) {
        List<AttendanceRecord> latest = attendanceRepository.findLatestPerAttendee(eventId);
        AttendanceRecord current = latest.stream()
                .filter(r -> matchesIdentity(r, snowflake, name))
                .findFirst()
                .orElse(null);

        AttendanceStatus newStatus;
        if (current != null && current.getStatus() == requestedStatus) {
            newStatus = AttendanceStatus.REMOVED;
        } else {
            newStatus = requestedStatus;
        }

        recordAttendance(eventId, snowflake, name, newStatus, null);

        if (newStatus == AttendanceStatus.DECLINED && featureToggles.isRemovePlusOnesOnDecline() && snowflake != null) {
            cascadeRemoveOwnedPlusOnes(eventId, snowflake, latest);
        }
    }

    public void removeAttendee(UUID eventId, String snowflake, String name) {
        recordAttendance(eventId, snowflake, name, AttendanceStatus.REMOVED, null);

        if (featureToggles.isRemovePlusOnesOnDecline() && snowflake != null) {
            List<AttendanceRecord> latest = attendanceRepository.findLatestPerAttendee(eventId);
            cascadeRemoveOwnedPlusOnes(eventId, snowflake, latest);
        }
    }

    public boolean isOwnerOfPlusOne(UUID eventId, String plusOneName, String userSnowflake) {
        List<AttendanceRecord> latest = attendanceRepository.findLatestPerAttendee(eventId);
        return latest.stream()
                .anyMatch(r -> r.getName() != null
                        && r.getName().equals(plusOneName)
                        && r.getSnowflake() == null
                        && userSnowflake.equals(r.getOwnerSnowflake())
                        && r.getStatus() != AttendanceStatus.REMOVED);
    }

    private void cascadeRemoveOwnedPlusOnes(UUID eventId, String ownerSnowflake, List<AttendanceRecord> latest) {
        latest.stream()
                .filter(r -> ownerSnowflake.equals(r.getOwnerSnowflake())
                        && r.getSnowflake() == null
                        && r.getStatus() != AttendanceStatus.REMOVED)
                .forEach(r -> {
                    log.info(
                            "Cascade-removing +1 '{}' owned by {} from event {}", r.getName(), ownerSnowflake, eventId);
                    recordAttendance(eventId, null, r.getName(), AttendanceStatus.REMOVED, r.getOwnerSnowflake());
                });
    }

    private boolean matchesIdentity(AttendanceRecord record, String snowflake, String name) {
        if (snowflake != null && !snowflake.isBlank()) {
            return snowflake.equals(record.getSnowflake());
        }
        return name != null && name.equals(record.getName());
    }
}
