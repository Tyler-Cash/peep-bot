# Rewind Social Graph Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the "always together" text list on the Rewind page with a d3 force-directed graph showing all co-attending user pairs as nodes (sized by attendance, decorated with avatar) and edges (weighted by shared events).

**Architecture:** Three new backend model records replace `SocialPairDto`; `RewindService` builds a `SocialGraphDto` instead of a pairs list. The frontend gets new TypeScript types and a new `SocialGraph` component that mounts a `<div>`, then uses a single `useEffect` to run d3 imperatively — the simulation drives DOM updates directly on each tick with no React re-renders.

**Tech Stack:** Spring Boot 3 / Java records (backend), React 19 / TypeScript / d3 v7 (frontend), Vitest (frontend tests), JUnit 5 + Mockito (backend tests).

---

## File Map

**Create:**
- `backend/src/main/java/dev/tylercash/event/rewind/model/SocialGraphDto.java`
- `backend/src/main/java/dev/tylercash/event/rewind/model/GraphNodeDto.java`
- `backend/src/main/java/dev/tylercash/event/rewind/model/GraphEdgeDto.java`
- `frontend/src/components/rewind/SocialGraph.tsx`

**Modify:**
- `backend/src/main/java/dev/tylercash/event/rewind/model/RewindStatsDto.java`
- `backend/src/main/java/dev/tylercash/event/rewind/RewindService.java`
- `frontend/src/lib/types.ts`
- `frontend/src/mocks/fixtures.ts`
- `frontend/src/components/rewind/Rewind.tsx`
- `frontend/package.json` (add `d3` dependency)

**Delete:**
- `backend/src/main/java/dev/tylercash/event/rewind/model/SocialPairDto.java`

---

### Task 1: Create backend graph model records

**Files:**
- Create: `backend/src/main/java/dev/tylercash/event/rewind/model/SocialGraphDto.java`
- Create: `backend/src/main/java/dev/tylercash/event/rewind/model/GraphNodeDto.java`
- Create: `backend/src/main/java/dev/tylercash/event/rewind/model/GraphEdgeDto.java`

- [ ] **Step 1: Create `GraphNodeDto.java`**

```java
package dev.tylercash.event.rewind.model;

public record GraphNodeDto(
        String snowflake,
        String displayName,
        String avatarUrl,
        int eventCount) {}
```

- [ ] **Step 2: Create `GraphEdgeDto.java`**

```java
package dev.tylercash.event.rewind.model;

public record GraphEdgeDto(
        String user1Snowflake,
        String user2Snowflake,
        int sharedEvents) {}
```

- [ ] **Step 3: Create `SocialGraphDto.java`**

```java
package dev.tylercash.event.rewind.model;

import java.util.List;

public record SocialGraphDto(
        List<GraphNodeDto> nodes,
        List<GraphEdgeDto> edges) {}
```

- [ ] **Step 4: Verify the three files compile**

Run from `backend/`:
```bash
./gradlew compileJava
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/rewind/model/SocialGraphDto.java \
        backend/src/main/java/dev/tylercash/event/rewind/model/GraphNodeDto.java \
        backend/src/main/java/dev/tylercash/event/rewind/model/GraphEdgeDto.java
git commit -m "feat(rewind): add SocialGraphDto, GraphNodeDto, GraphEdgeDto records"
```

---

### Task 2: Replace `SocialPairDto` with `SocialGraphDto` in `RewindStatsDto`

**Files:**
- Modify: `backend/src/main/java/dev/tylercash/event/rewind/model/RewindStatsDto.java`
- Delete: `backend/src/main/java/dev/tylercash/event/rewind/model/SocialPairDto.java`

- [ ] **Step 1: Update `RewindStatsDto.java`**

Replace the entire file content:

```java
package dev.tylercash.event.rewind.model;

import java.util.List;
import java.util.Map;

public record RewindStatsDto(
        int totalEvents,
        int totalUniqueAttendees,
        int totalRsvps,
        double averageGroupSize,
        List<EventCategoryDto> topCategories,
        List<AttendeeStatDto> topAttendees,
        List<AttendeeStatDto> topOrganizers,
        SocialGraphDto socialGraph,
        Map<String, Integer> eventsByMonth,
        Map<String, Integer> eventsByDayOfWeek,
        EventSummaryDto firstEvent,
        EventSummaryDto lastEvent,
        int totalPlusOneGuests,
        boolean embeddingsAvailable,
        Integer year) {}
```

- [ ] **Step 2: Delete `SocialPairDto.java`**

```bash
rm backend/src/main/java/dev/tylercash/event/rewind/model/SocialPairDto.java
```

- [ ] **Step 3: Verify the test still compiles (it mocks `RewindStatsDto` so it doesn't reference removed fields)**

```bash
./gradlew compileTestJava
```
Expected: `BUILD SUCCESSFUL` — `RewindControllerTest` mocks the DTO so field changes don't break it.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/rewind/model/RewindStatsDto.java
git rm backend/src/main/java/dev/tylercash/event/rewind/model/SocialPairDto.java
git commit -m "feat(rewind): replace topSocialPairs with socialGraph in RewindStatsDto"
```

---

### Task 3: Update `RewindService` to build `SocialGraphDto`

**Files:**
- Modify: `backend/src/main/java/dev/tylercash/event/rewind/RewindService.java`

- [ ] **Step 1: Update the social-pairs section in `buildStats`**

In `RewindService.java`, find the block that starts with `// Social pairs (guild-wide only)` (around line 175) and replace it entirely:

```java
        // Social graph (guild-wide only)
        SocialGraphDto socialGraph = null;
        if (!personal) {
            String pairsQ = "SELECT a1.snowflake AS u1, a2.snowflake AS u2, COUNT(DISTINCT a1.event_id) AS shared "
                    + "FROM attendance a1 "
                    + "JOIN attendance a2 ON a1.event_id = a2.event_id AND a1.snowflake < a2.snowflake "
                    + "JOIN event e ON a1.event_id = e.id "
                    + "WHERE a1.status = 'ACCEPTED' AND a2.status = 'ACCEPTED'" + yf + gf
                    + " GROUP BY a1.snowflake, a2.snowflake ORDER BY shared DESC";
            var pq = em.createNativeQuery(pairsQ);
            if (year != null) pq.setParameter("year", year);
            pq.setParameter("guildId", guildId);
            List<Object[]> pairRows = pq.getResultList();

            Set<String> graphSnowflakes = new HashSet<>();
            pairRows.forEach(r -> {
                graphSnowflakes.add((String) r[0]);
                graphSnowflakes.add((String) r[1]);
            });

            List<GraphEdgeDto> edges = pairRows.stream()
                    .map(r -> new GraphEdgeDto(
                            (String) r[0],
                            (String) r[1],
                            ((Number) r[2]).intValue()))
                    .collect(Collectors.toList());

            List<GraphNodeDto> nodes = new ArrayList<>();
            if (!graphSnowflakes.isEmpty()) {
                String nodeCountQ = "SELECT a.snowflake, COUNT(DISTINCT a.event_id) as cnt "
                        + "FROM attendance a JOIN event e ON a.event_id = e.id "
                        + "WHERE a.status = 'ACCEPTED' AND a.snowflake IN (:snowflakes)" + yf + gf
                        + " GROUP BY a.snowflake";
                var nq = em.createNativeQuery(nodeCountQ);
                nq.setParameter("snowflakes", graphSnowflakes);
                if (year != null) nq.setParameter("year", year);
                nq.setParameter("guildId", guildId);
                List<Object[]> nodeRows = nq.getResultList();

                Map<String, String> nodeNames = userCacheService.getDisplayNames(graphSnowflakes);
                nodes = nodeRows.stream()
                        .map(r -> {
                            String snowflake = (String) r[0];
                            return new GraphNodeDto(
                                    snowflake,
                                    nodeNames.getOrDefault(snowflake, "Unknown"),
                                    "/api/avatar/" + snowflake,
                                    ((Number) r[1]).intValue());
                        })
                        .collect(Collectors.toList());
            }

            socialGraph = new SocialGraphDto(nodes, edges);
        }
```

- [ ] **Step 2: Update the `return` statement in `buildStats`**

Replace `topSocialPairs,` with `socialGraph,` in the `new RewindStatsDto(...)` call (around line 292). The full constructor should read:

```java
        return new RewindStatsDto(
                totalEvents,
                totalUniqueAttendees,
                totalRsvps,
                averageGroupSize,
                topCategories,
                topAttendees,
                topOrganizers,
                socialGraph,
                eventsByMonth,
                eventsByDayOfWeek,
                firstEvent,
                lastEvent,
                totalPlusOneGuests,
                embeddingService.isEmbeddingsAvailable(),
                year);
```

- [ ] **Step 3: Fix imports in `RewindService.java`**

Remove the `SocialPairDto` import and add the three new imports. The import block should contain:

```java
import dev.tylercash.event.rewind.model.*;
```

(The wildcard already covers all model classes — check that the file already uses `model.*` or add individual imports for `GraphEdgeDto`, `GraphNodeDto`, `SocialGraphDto`.)

- [ ] **Step 4: Run the full backend test suite**

```bash
./gradlew test
```
Expected: `BUILD SUCCESSFUL` — all existing tests pass.

- [ ] **Step 5: Apply Spotless formatting**

```bash
./gradlew spotlessApply
```

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/rewind/RewindService.java
git commit -m "feat(rewind): build SocialGraphDto from co-attendance data"
```

---

### Task 4: Update frontend types and mock fixture

**Files:**
- Modify: `frontend/src/lib/types.ts`
- Modify: `frontend/src/mocks/fixtures.ts`

- [ ] **Step 1: Update `types.ts`**

Remove these lines from `types.ts`:

```ts
export type SocialPairDto = {
  user1: string;
  user2: string;
  sharedEvents: number;
};
```

Replace `topSocialPairs: SocialPairDto[];` in `RewindStats` with `socialGraph: SocialGraphDto | null;`.

Add the three new types:

```ts
export type SocialGraphDto = {
  nodes: GraphNodeDto[];
  edges: GraphEdgeDto[];
};

export type GraphNodeDto = {
  snowflake: string;
  displayName: string;
  avatarUrl: string | null;
  eventCount: number;
};

export type GraphEdgeDto = {
  user1Snowflake: string;
  user2Snowflake: string;
  sharedEvents: number;
};
```

The updated `RewindStats` type should read:

```ts
export type RewindStats = {
  year: number;
  totalEvents: number;
  totalUniqueAttendees: number;
  totalRsvps: number;
  averageGroupSize: number;
  topCategories: EventCategoryDto[];
  topAttendees: AttendeeStatDto[];
  topOrganizers: AttendeeStatDto[];
  socialGraph: SocialGraphDto | null;
  eventsByMonth: Record<string, number>;
  eventsByDayOfWeek: Record<string, number>;
  firstEvent: EventSummaryDto | null;
  lastEvent: EventSummaryDto | null;
  totalPlusOneGuests: number;
  embeddingsAvailable: boolean;
};
```

- [ ] **Step 2: Update `fixtures.ts`**

In `frontend/src/mocks/fixtures.ts`, replace the `topSocialPairs` entry in `rewindStats`:

Remove:
```ts
  topSocialPairs: [
    { user1: "Otis", user2: "Mira", sharedEvents: 11 },
    { user1: "Bas", user2: "Nim", sharedEvents: 9 },
  ],
```

Add:
```ts
  socialGraph: {
    nodes: [
      { snowflake: "s1", displayName: "Otis", avatarUrl: null, eventCount: 12 },
      { snowflake: "s2", displayName: "Mira", avatarUrl: null, eventCount: 11 },
      { snowflake: "s3", displayName: "Bas", avatarUrl: null, eventCount: 8 },
      { snowflake: "s4", displayName: "Nim", avatarUrl: null, eventCount: 7 },
    ],
    edges: [
      { user1Snowflake: "s1", user2Snowflake: "s2", sharedEvents: 11 },
      { user1Snowflake: "s3", user2Snowflake: "s4", sharedEvents: 9 },
      { user1Snowflake: "s1", user2Snowflake: "s3", sharedEvents: 6 },
    ],
  },
```

- [ ] **Step 3: Check TypeScript compiles**

Run from `frontend/`:
```bash
export NVM_DIR="$HOME/.nvm" && [ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh"
npx tsc --noEmit
```
Expected: no errors.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/lib/types.ts frontend/src/mocks/fixtures.ts
git commit -m "feat(rewind): update frontend types and fixtures for SocialGraphDto"
```

---

### Task 5: Install d3

**Files:**
- Modify: `frontend/package.json`

- [ ] **Step 1: Install d3 and its types**

Run from `frontend/`:
```bash
export NVM_DIR="$HOME/.nvm" && [ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh"
npm install d3
npm install --save-dev @types/d3
```

- [ ] **Step 2: Verify TypeScript still compiles**

```bash
npx tsc --noEmit
```
Expected: no errors.

- [ ] **Step 3: Commit**

```bash
git add frontend/package.json frontend/package-lock.json
git commit -m "chore(deps): add d3 and @types/d3"
```

---

### Task 6: Create `SocialGraph` component

**Files:**
- Create: `frontend/src/components/rewind/SocialGraph.tsx`

- [ ] **Step 1: Create `SocialGraph.tsx`**

```tsx
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
  d3.SimulationLinkDatum<SimNode> & { sharedEvents: number };

export function SocialGraph({ graph }: { graph: SocialGraphDto }) {
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const container = containerRef.current;
    if (!container || graph.nodes.length === 0) return;

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

    const defs = svg.append("defs");
    graph.nodes.forEach((node) => {
      const r = radii.get(node.snowflake)!;
      defs
        .append("clipPath")
        .attr("id", `clip-${node.snowflake}`)
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

    nodeEls
      .append("image")
      .attr("href", (d) => d.avatarUrl ?? "")
      .attr("x", (d) => -radii.get(d.snowflake)!)
      .attr("y", (d) => -radii.get(d.snowflake)!)
      .attr("width", (d) => radii.get(d.snowflake)! * 2)
      .attr("height", (d) => radii.get(d.snowflake)! * 2)
      .attr("clip-path", (d) => `url(#clip-${d.snowflake})`)
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

    nodeEls
      .transition()
      .delay((_, i) => i * 30)
      .duration(300)
      .style("opacity", 1);

    // Higher shared events → shorter edge distance (pulls frequent pairs closer)
    const linkDistanceScale = d3
      .scaleLinear()
      .domain([minShared, maxShared])
      .range([200, 50]);

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
      // Low strength (0.05) keeps nodes loosely drifting toward center without forcing a tight cluster
      .force("center", d3.forceCenter(width / 2, height / 2).strength(0.05))
      .force(
        "collide",
        d3.forceCollide<SimNode>((d) => (radii.get(d.snowflake) ?? MIN_RADIUS) + 4),
      );

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

    return () => {
      simulation.stop();
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
```

- [ ] **Step 2: Check TypeScript compiles**

Run from `frontend/`:
```bash
export NVM_DIR="$HOME/.nvm" && [ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh"
npx tsc --noEmit
```
Expected: no errors.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/components/rewind/SocialGraph.tsx
git commit -m "feat(rewind): add SocialGraph d3 force-directed component"
```

---

### Task 7: Wire `SocialGraph` into `Rewind.tsx`

**Files:**
- Modify: `frontend/src/components/rewind/Rewind.tsx`

- [ ] **Step 1: Add the import at the top of `Rewind.tsx`**

Below the existing imports, add:

```tsx
import { SocialGraph } from "@/components/rewind/SocialGraph";
```

- [ ] **Step 2: Replace the "always together" section**

Find and remove the entire block (lines 163–179):

```tsx
          {/* top social pairs */}
          {data.topSocialPairs.length > 0 && (
            <Slab className="p-5">
              <span className="text-[11px] font-extrabold tracking-[0.18em] text-mute uppercase">
                always together
              </span>
              <ul className="mt-3 flex flex-col gap-2">
                {data.topSocialPairs.map((pair, i) => (
                  <li key={i} className="flex items-center gap-2 text-[14px]">
                    <span className="font-extrabold">{pair.user1}</span>
                    <span className="text-mute">+</span>
                    <span className="font-extrabold">{pair.user2}</span>
                    <span className="ml-auto text-mute font-semibold">{pair.sharedEvents} events</span>
                  </li>
                ))}
              </ul>
            </Slab>
          )}
```

Replace with:

```tsx
          {/* social graph */}
          {data.socialGraph && (
            <Slab className="p-5">
              <span className="text-[11px] font-extrabold tracking-[0.18em] text-mute uppercase">
                social graph
              </span>
              <SocialGraph graph={data.socialGraph} />
            </Slab>
          )}
```

- [ ] **Step 3: Check TypeScript compiles**

```bash
export NVM_DIR="$HOME/.nvm" && [ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh"
npx tsc --noEmit
```
Expected: no errors.

- [ ] **Step 4: Run lint and format**

```bash
npm run lint
npm run format:check
```
Fix any issues with `npm run lint:fix` and `npm run format` if needed.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/rewind/Rewind.tsx
git commit -m "feat(rewind): replace always-together list with SocialGraph component"
```

---

### Task 8: Final verification

- [ ] **Step 1: Run all backend tests**

From `backend/`:
```bash
./gradlew test
```
Expected: `BUILD SUCCESSFUL`, all tests green.

- [ ] **Step 2: Run Spotless check**

```bash
./gradlew spotlessCheck
```
Expected: `BUILD SUCCESSFUL`. If it fails, run `./gradlew spotlessApply` then re-check.

- [ ] **Step 3: Run frontend tests**

From `frontend/`:
```bash
export NVM_DIR="$HOME/.nvm" && [ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh"
npm test
```
Expected: all tests pass.

- [ ] **Step 4: Run frontend lint and format check**

```bash
npm run lint
npm run format:check
```
Expected: no errors.

- [ ] **Step 5: Start the dev server and verify the graph renders**

Start the backend (see CLAUDE.md) and frontend (`npm run dev`), navigate to `http://localhost:5173/rewind`, select guild scope, and confirm:
- The "always together" text list is gone
- A "social graph" section appears with a force-directed SVG
- Nodes burst outward from the centre on first load
- Nodes are sized differently based on attendance
- Edges connect co-attendees
- Dragging a node works
- Hovering shows a tooltip with name and event count
- Personal scope ("just me") shows no graph section
