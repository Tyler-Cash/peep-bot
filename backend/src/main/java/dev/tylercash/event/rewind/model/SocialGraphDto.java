package dev.tylercash.event.rewind.model;

import java.util.List;

public record SocialGraphDto(
        List<GraphNodeDto> nodes,
        List<GraphEdgeDto> edges) {}
