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
    let bgColor = "#ffffff";
    let bgEl: Element | null = container;
    while (bgEl) {
      const c = getComputedStyle(bgEl).backgroundColor;
      if (c !== "rgba(0, 0, 0, 0)" && c !== "transparent") {
        bgColor = c;
        break;
      }
      bgEl = bgEl.parentElement;
    }

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

    // Higher shared events → slightly tighter edge; baseline added per-link from node radii below
    const sharedDistanceScale = d3
      .scaleLinear()
      .domain([minShared, maxShared])
      .range([155, 55]);

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

    const degree = new Map<string, number>(
      graph.nodes.map((n) => [n.snowflake, 0]),
    );
    links.forEach((l) => {
      const s = l.source as string;
      const t = l.target as string;
      degree.set(s, (degree.get(s) ?? 0) + 1);
      degree.set(t, (degree.get(t) ?? 0) + 1);
    });

    const linkEls = svg
      .append("g")
      .selectAll("line")
      .data(links)
      .enter()
      .append("line")
      .attr("stroke", MUTE)
      .attr("stroke-opacity", 0.45)
      .attr("stroke-width", (d) => strokeScale(d.sharedEvents));

    const tooltip = d3
      .select(container)
      .append("div")
      .style("position", "absolute")
      .style("pointer-events", "none")
      .style("opacity", "0")
      .style("background", "#F5F0E8")
      .style("border", `1.5px solid ${INK}`)
      .style("border-radius", "6px")
      .style("box-shadow", `2px 2px 0 ${INK}`)
      .style("padding", "6px 10px")
      .style("font-size", "12px")
      .style("font-weight", "600")
      .style("color", INK)
      .style("white-space", "nowrap")
      .style("z-index", "10");

    // Wide transparent lines layered on top for easy hover / tooltip targeting
    const linkHitEls = svg
      .append("g")
      .selectAll<SVGLineElement, SimLink>("line")
      .data(links)
      .enter()
      .append("line")
      .attr("stroke", "rgba(0,0,0,0)")
      .attr("stroke-width", 12)
      .style("pointer-events", "stroke")
      .attr("cursor", "default");

    linkHitEls
      .on("mouseover", (_event, d) => {
        tooltip
          .text(
            `${d.displayName1} and ${d.displayName2} · ${d.sharedEvents} events together`,
          )
          .style("opacity", "1");
      })
      .on("mousemove", (event) => {
        const [x, y] = d3.pointer(event, container);
        tooltip
          .style("left", `${x + 12}px`)
          .style("top", `${y - 28}px`);
      })
      .on("mouseout", () => {
        tooltip.style("opacity", "0");
      });

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
      .style("user-select", "none")
      .style("pointer-events", "none")
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
      .attr("stroke", bgColor)
      .attr("stroke-width", 4)
      .attr("stroke-linejoin", "round")
      .style("paint-order", "stroke fill")
      .style("user-select", "none")
      .style("pointer-events", "none")
      .text((d) =>
        d.displayName.length > 12
          ? d.displayName.slice(0, 11) + "…"
          : d.displayName,
      );

    // Approximate label half-width so collision keeps text from overlapping.
    // Labels use font-size 11, font-weight 700; ~6.6px per char is a safe estimate,
    // and we cap at 12 chars + ellipsis to match the truncation above.
    const labelHalfWidth = (name: string) =>
      (Math.min(name.length, 12) * 6.6) / 2 + 4;

    const collisionRadius = (d: SimNode) => {
      const r = radii.get(d.snowflake) ?? MIN_RADIUS;
      const labelR = labelHalfWidth(d.displayName);
      // Label sits below the node and is ~14px tall; pad enough that adjacent
      // labels don't kiss. Use the larger of circle radius and label half-width.
      return Math.max(r + 22, labelR + 10);
    };

    const maxDegree = Math.max(1, d3.max(degree.values()) ?? 1);
    const cx = width / 2;
    const cy = height / 2;
    // Semi-axes for the outer ellipse. Using width/2 fills the horizontal
    // space instead of leaving the dead bands a circular layout produces.
    const semiX = width / 2 - 50;
    const semiY = height / 2 - 40;

    const ellipseRadial = (alpha: number) => {
      for (const n of nodes) {
        const deg = degree.get(n.snowflake) ?? 0;
        const t = 1 - deg / maxDegree; // 0 for hubs, 1 for isolated
        // Inner ellipse for hubs (~25% of the outer), outer ellipse for the
        // most isolated nodes; smooth interpolation in between.
        const ax = semiX * (0.25 + 0.75 * t);
        const ay = semiY * (0.25 + 0.75 * t);
        const dx = (n.x ?? cx) - cx;
        const dy = (n.y ?? cy) - cy;
        const angle = Math.atan2(dy, dx);
        const targetX = cx + Math.cos(angle) * ax;
        const targetY = cy + Math.sin(angle) * ay;
        const strength = (0.04 + 0.35 * t) * alpha;
        n.vx = (n.vx ?? 0) + (targetX - (n.x ?? cx)) * strength;
        n.vy = (n.vy ?? 0) + (targetY - (n.y ?? cy)) * strength;
      }
    };

    const linkRepulsion = (alpha: number) => {
      const padding = 14;
      for (const link of links) {
        const a = link.source as SimNode;
        const b = link.target as SimNode;
        const ax = a.x ?? 0;
        const ay = a.y ?? 0;
        const bx = b.x ?? 0;
        const by = b.y ?? 0;
        const ex = bx - ax;
        const ey = by - ay;
        const lenSq = ex * ex + ey * ey;
        if (lenSq < 1) continue;
        for (const c of nodes) {
          if (c.snowflake === a.snowflake || c.snowflake === b.snowflake) continue;
          const ncx = c.x ?? 0;
          const ncy = c.y ?? 0;
          const tProj = ((ncx - ax) * ex + (ncy - ay) * ey) / lenSq;
          if (tProj < 0.05 || tProj > 0.95) continue;
          const projX = ax + tProj * ex;
          const projY = ay + tProj * ey;
          const offX = ncx - projX;
          const offY = ncy - projY;
          const dist = Math.sqrt(offX * offX + offY * offY);
          const r = (radii.get(c.snowflake) ?? MIN_RADIUS) + padding;
          if (dist > r || dist < 0.5) continue;
          const push = ((r - dist) / r) * alpha * 4;
          c.vx = (c.vx ?? 0) + (offX / dist) * push;
          c.vy = (c.vy ?? 0) + (offY / dist) * push;
        }
      }
    };

    const simulation = d3
      .forceSimulation<SimNode>(nodes)
      .force(
        "link",
        d3
          .forceLink<SimNode, SimLink>(links)
          .id((d) => d.snowflake)
          .distance((d) => {
            const s = d.source as SimNode;
            const t = d.target as SimNode;
            const rSum =
              (radii.get(s.snowflake) ?? MIN_RADIUS) +
              (radii.get(t.snowflake) ?? MIN_RADIUS);
            return 70 + rSum + sharedDistanceScale(d.sharedEvents);
          }),
      )
      // Mass-aware repulsion: hubs (high degree) and large nodes push harder,
      // which is what GEM adds on top of plain Fruchterman-Reingold.
      .force(
        "charge",
        d3
          .forceManyBody<SimNode>()
          .strength((d) => {
            const deg = degree.get(d.snowflake) ?? 0;
            const r = radii.get(d.snowflake) ?? MIN_RADIUS;
            return -300 - deg * 60 - r * 8;
          })
          .distanceMax(width),
      )
      // forceCenter shifts the centroid to the viewport middle every tick
      // without pulling nodes inward, so the cluster stays centered no matter
      // how it ends up shaped.
      .force("center", d3.forceCenter<SimNode>(width / 2, height / 2))
      // Very gentle inward pull keeps stragglers from drifting to the edges
      // during long settles.
      .force("x", d3.forceX<SimNode>(width / 2).strength(0.008))
      .force("y", d3.forceY<SimNode>(height / 2).strength(0.008))
      .force(
        "collide",
        d3.forceCollide<SimNode>(collisionRadius).strength(0.9).iterations(2),
      )
      // Elliptical radial: hubs sit near the center, low-degree nodes get
      // pulled toward the viewport ellipse. Using an ellipse (vs. forceRadial's
      // circle) means wide viewports actually fill their horizontal space
      // instead of leaving dead bands on the left/right.
      .force("ellipse", ellipseRadial)
      // Push nodes off of edges they aren't part of so links don't run through
      // unrelated avatars. This is what most "tidy graph" tools call
      // node-edge repulsion — also reduces crossings as a side effect.
      .force("linkRepulsion", linkRepulsion)
      .stop();

    const redraw = () => {
      nodes.forEach((d) => {
        const r = radii.get(d.snowflake) ?? MIN_RADIUS;
        d.x = Math.max(r + 4, Math.min(width - r - 4, d.x ?? 0));
        // extra bottom margin for label text
        d.y = Math.max(r + 4, Math.min(height - r - 22, d.y ?? 0));
      });

      const x1 = (d: SimLink) => (d.source as SimNode).x ?? 0;
      const y1 = (d: SimLink) => (d.source as SimNode).y ?? 0;
      const x2 = (d: SimLink) => (d.target as SimNode).x ?? 0;
      const y2 = (d: SimLink) => (d.target as SimNode).y ?? 0;

      linkEls.attr("x1", x1).attr("y1", y1).attr("x2", x2).attr("y2", y2);
      linkHitEls.attr("x1", x1).attr("y1", y1).attr("x2", x2).attr("y2", y2);
      nodeEls.attr("transform", (d) => `translate(${d.x ?? 0},${d.y ?? 0})`);
    };

    simulation.on("tick", redraw);

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

    nodeEls
      .on("mouseover", (_event, d) => {
        const isAdjacent = (l: SimLink) => {
          const src = (l.source as SimNode).snowflake;
          const tgt = (l.target as SimNode).snowflake;
          return src === d.snowflake || tgt === d.snowflake;
        };
        linkEls
          .attr("stroke", (l) => (isAdjacent(l) ? INK : MUTE))
          .attr("stroke-opacity", (l) => (isAdjacent(l) ? 0.85 : 0.06))
          .attr("stroke-width", (l) =>
            isAdjacent(l)
              ? strokeScale(l.sharedEvents) * 1.5
              : strokeScale(l.sharedEvents),
          );
        const neighbors = new Set<string>([d.snowflake]);
        links.forEach((l) => {
          const src = (l.source as SimNode).snowflake;
          const tgt = (l.target as SimNode).snowflake;
          if (src === d.snowflake) neighbors.add(tgt);
          if (tgt === d.snowflake) neighbors.add(src);
        });
        nodeEls.style("opacity", (n) => (neighbors.has(n.snowflake) ? 1 : 0.2));
      })
      .on("mouseout", () => {
        linkEls
          .attr("stroke", MUTE)
          .attr("stroke-opacity", 0.45)
          .attr("stroke-width", (l) => strokeScale(l.sharedEvents));
        nodeEls.style("opacity", 1);
      });

    // Seed all nodes in a tight, deterministic ring around center. The visible
    // animation lets the force simulation push them apart — charge repulsion,
    // collide, and link springs produce real bouncing and settling rather than
    // a uniform zoom.
    nodes.forEach((n, i) => {
      const angle = (i / nodes.length) * Math.PI * 2;
      n.x = width / 2 + Math.cos(angle) * 8;
      n.y = height / 2 + Math.sin(angle) * 8;
      n.vx = 0;
      n.vy = 0;
    });
    redraw();

    // Slow the cooling so the settle is visible for ~3-4s. Default alphaDecay
    // (~0.0228) settles in ~300 ticks; halving it gives a longer, more
    // physical-looking balance pass with visible collisions.
    simulation.alphaDecay(0.010).velocityDecay(0.28);

    // Delay animation + simulation start until the graph scrolls into view
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0].isIntersecting) {
          nodeEls
            .transition()
            .delay((_, i) => i * 30)
            .duration(300)
            .style("opacity", 1);
          // alpha > 1 gives the forces extra "kick" up front so the explosion
          // is energetic before collide and links pull things into balance.
          simulation.alpha(1.7).restart();
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
      style={{ height: SVG_HEIGHT, position: "relative" }}
    />
  );
}
