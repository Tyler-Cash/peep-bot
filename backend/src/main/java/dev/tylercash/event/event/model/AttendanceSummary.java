package dev.tylercash.event.event.model;

import java.util.List;

public record AttendanceSummary(
        List<AttendanceRecord> accepted, List<AttendanceRecord> declined, List<AttendanceRecord> maybe) {}
