"use client";

import { useEffect, useRef } from "react";
import * as d3 from "d3";
import { stringToColor } from "@/lib/format";
import type { SocialGraphDto, GraphNodeDto, GraphEdgeDto } from "@/lib/types";

const MIN_RADIUS = 12;
const MAX_RADIUS = 30;
const SVG_HEIGHT = 520;
const INK = "#0E100D";
const MUTE = "#6B6E66";

type SimNode = GraphNodeDto & d3.SimulationNodeDatum;
type SimLink = Omit<GraphEdgeDto, "user1Snowflake" | "user2Snowflake"> &
  d3.SimulationLinkDatum<SimNode> & {
    sharedEvents: number;
    displayName1: string;
    displayName2: string;
  };

export function SocialGraph({ graph }: { graph: SocialGraphDto }) {
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const container = containerRef.current;
    if (!container || graph.nodes.length === 0) return;

    const uid = Math.random().toString(36).slice(2, 8);
    const width = container.clientWidth || 600;
    const height = SVG_HEIGHT;

    d3.select(container).selectAll("*").remove();

    const svg = d3
      .select(container)
      .append("svg")
      .attr("width", width)
      .attr("height", height)
      .attr("viewBox", `0 0 ${width} ${height}`);

    const maxCount = d3.max(graph.nodes, (n) => n.eventCount) ?? 1;
    const minCount = d3.min(graph.nodes, (n) => n.eventCount) ?? 1;
    const radiusScale = d3
      .scaleSqrt()
      .domain([minCount, maxCount])
      .range([MIN_RADIUS, MAX_RADIUS]);
    const radii = new Map(
      graph.nodes.map((n) => [n.snowflake, radiusScale(n.eventCount)]),
    );

    const maxShared = d3.max(graph.edges, (e) => e.sharedEvents) ?? 1;
    const minShared = d3.min(graph.edges, (e) => e.sharedEvents) ?? 1;
    const strokeScale = d3
      .scaleLinear()
      .domain([minShared, maxShared])
      .range([1, 6]);

    // Higher shared events → shorter edge distance; minimum of 80px to keep nodes readable
    const linkDistanceScale = d3
      .scaleLinear()
      .domain([minShared, maxShared])
      .range([200, 80]);

    const nameBySnowflake = new Map(
      graph.nodes.map((n) => [n.snowflake, n.displayName]),
    );

    const defs = svg.append("defs");
    graph.nodes.forEach((node) => {
      const r = radii.get(node.snowflake)!;
      defs
        .append("clipPath")
        .attr("id", `clip-${uid}-${node.snowflake}`)
        .append("circle")
        .attr("cx", 0)
        .attr("cy", 0)
        .attr("r", r);
    });

    const nodes: SimNode[] = graph.nodes.map((n) => ({
      ...n,
      x: width / 2,
      y: height / 2,
    }));

    const links: SimLink[] = graph.edges.map((e) => ({
      source: e.user1Snowflake,
      target: e.user2Snowflake,
      sharedEvents: e.sharedEvents,
      displayName1: nameBySnowflake.get(e.user1Snowflake) ?? e.user1Snowflake,
      displayName2: nameBySnowflake.get(e.user2Snowflake) ?? e.user2Snowflake,
    }));

    const linkEls = svg
      .append("g")
      .selectAll("line")
      .data(links)
      .enter()
      .append("line")
      .attr("stroke", MUTE)
      .attr("stroke-opacity", 0.45)
      .attr("stroke-width", (d) => strokeScale(d.sharedEvents));

    linkEls
      .append("title")
      .text(
        (d) =>
          `${d.displayName1} and ${d.displayName2} attended ${d.sharedEvents} events together`,
      );

    const nodeEls = svg
      .append("g")
      .selectAll<SVGGElement, SimNode>("g")
      .data(nodes)
      .enter()
      .append("g")
      .style("opacity", 0)
      .style("cursor", "grab");

    nodeEls
      .append("circle")
      .attr("r", (d) => radii.get(d.snowflake)!)
      .attr("fill", (d) => stringToColor(d.displayName))
      .attr("stroke", INK)
      .attr("stroke-width", 1.5);

    // First-letter fallback — shown only when no avatar image is available
    nodeEls
      .append("text")
      .attr("text-anchor", "middle")
      .attr("dominant-baseline", "central")
      .attr("font-size", (d) => Math.round(radii.get(d.snowflake)! * 0.85))
      .attr("font-weight", 700)
      .attr("fill", INK)
      .style("display", (d) => (d.avatarUrl ? "none" : null))
      .text((d) => d.displayName[0].toUpperCase());

    nodeEls
      .append("image")
      .attr("href", (d) => d.avatarUrl)
      .attr("x", (d) => -radii.get(d.snowflake)!)
      .attr("y", (d) => -radii.get(d.snowflake)!)
      .attr("width", (d) => radii.get(d.snowflake)! * 2)
      .attr("height", (d) => radii.get(d.snowflake)! * 2)
      .attr("clip-path", (d) => `url(#clip-${uid}-${d.snowflake})`)
      .style("display", (d) => (d.avatarUrl ? null : "none"));

    nodeEls
      .append("text")
      .attr("text-anchor", "middle")
      .attr("dy", (d) => radii.get(d.snowflake)! + 13)
      .attr("font-size", 11)
      .attr("font-weight", 700)
      .attr("fill", INK)
      .text((d) =>
        d.displayName.length > 12
          ? d.displayName.slice(0, 11) + "…"
          : d.displayName,
      );

    nodeEls
      .append("title")
      .text((d) => `${d.displayName} · ${d.eventCount} events`);

    const simulation = d3
      .forceSimulation<SimNode>(nodes)
      .force(
        "link",
        d3
          .forceLink<SimNode, SimLink>(links)
          .id((d) => d.snowflake)
          .distance((d) => linkDistanceScale(d.sharedEvents)),
      )
      .force("charge", d3.forceManyBody().strength(-200))
      // Low strength keeps nodes loosely drifting toward center without forcing a tight cluster
      .force("center", d3.forceCenter(width / 2, height / 2).strength(0.05))
      .force(
        "collide",
        d3.forceCollide<SimNode>(
          (d) => (radii.get(d.snowflake) ?? MIN_RADIUS) + 4,
        ),
      )
      .stop();

    simulation.on("tick", () => {
      linkEls
        .attr("x1", (d) => (d.source as SimNode).x ?? 0)
        .attr("y1", (d) => (d.source as SimNode).y ?? 0)
        .attr("x2", (d) => (d.target as SimNode).x ?? 0)
        .attr("y2", (d) => (d.target as SimNode).y ?? 0);

      nodeEls.attr("transform", (d) => `translate(${d.x ?? 0},${d.y ?? 0})`);
    });

    const drag = d3
      .drag<SVGGElement, SimNode>()
      .on("start", (event, d) => {
        if (!event.active) simulation.alphaTarget(0.3).restart();
        d.fx = d.x;
        d.fy = d.y;
      })
      .on("drag", (event, d) => {
        d.fx = event.x;
        d.fy = event.y;
      })
      .on("end", (event, d) => {
        if (!event.active) simulation.alphaTarget(0);
        d.fx = null;
        d.fy = null;
      });

    nodeEls.call(drag);

    // Delay animation + simulation start until the graph scrolls into view
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0].isIntersecting) {
          nodeEls
            .transition()
            .delay((_, i) => i * 30)
            .duration(300)
            .style("opacity", 1);
          simulation.alpha(1).restart();
          observer.disconnect();
        }
      },
      { threshold: 0.1 },
    );
    observer.observe(container);

    return () => {
      simulation.stop();
      observer.disconnect();
    };
  }, [graph]);

  return (
    <div
      ref={containerRef}
      className="w-full mt-3"
      style={{ height: SVG_HEIGHT }}
    />
  );
}
