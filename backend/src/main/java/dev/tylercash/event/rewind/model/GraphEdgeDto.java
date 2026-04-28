package dev.tylercash.event.rewind.model;

public record GraphEdgeDto(
        String user1Snowflake,
        String user2Snowflake,
        int sharedEvents) {}
