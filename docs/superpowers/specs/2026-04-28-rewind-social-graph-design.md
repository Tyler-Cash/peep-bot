# Rewind Social Graph — Design Spec

**Date:** 2026-04-28
**Status:** Approved

## Overview

Replace the "always together" text list on the Rewind page with a force-directed graph visualisation. Nodes represent attendees (sized by total events attended, decorated with their profile picture). Edges connect any two users who have co-attended at least one event, with stroke width proportional to the number of shared events. On first render the graph animates outward from the centre like bubbles bursting apart as the simulation cools.

Scope: guild view only for this iteration. Personal view and a cross-guild graph endpoint are deferred.

---

## Backend

### Data model changes

`SocialPairDto` is deleted. `topSocialPairs: List<SocialPairDto>` is removed from `RewindStatsDto` and replaced with `socialGraph: SocialGraphDto` (null when in personal scope).

New records in `dev.tylercash.event.rewind.model`:

```java
public record SocialGraphDto(List<GraphNodeDto> nodes, List<GraphEdgeDto> edges) {}

public record GraphNodeDto(
    String snowflake,
    String displayName,
    String avatarUrl,
    int eventCount) {}

public record GraphEdgeDto(
    String user1Snowflake,
    String user2Snowflake,
    int sharedEvents) {}
```

### Query changes in `RewindService`

The existing pairs SQL is kept but the `LIMIT 20` is raised to `Integer.MAX_VALUE` (i.e., no practical limit — all co-attending pairs are returned). Edge rows carry snowflakes directly, not display names.

A second query derives per-node data: for every unique snowflake appearing in the edge set, fetch `eventCount` (ACCEPTED attendances in the selected year/guild) and resolve `displayName` via the existing `DiscordUserCacheService`. Avatar URL is `/api/avatar/{snowflake}` (same pattern as `topAttendees`).

`socialGraph` is set to `null` when `personal == true`. Personal-scope graph support is deferred.

---

## Frontend

### Type changes (`src/lib/types.ts`)

`SocialPairDto` is removed. `RewindStats.topSocialPairs` is replaced with:

```ts
socialGraph: SocialGraphDto | null;
```

New types added:

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

### New component: `SocialGraph.tsx`

Location: `frontend/src/components/rewind/SocialGraph.tsx`

Props: `{ graph: SocialGraphDto }`.

The component renders a `<div ref={containerRef}>` and a single `useEffect` that fires when `graph` changes. All d3 work happens inside that effect — React never re-renders during simulation ticks.

**SVG setup**
- The SVG fills the container width; height is fixed (e.g. 500px) or aspect-ratio-driven.
- `<defs>` contains one `<clipPath id="clip-{snowflake}">` per node, each holding a `<circle>` at the node's origin. This enables circular profile picture cropping.

**Edges**
- Rendered as `<line>` elements.
- `stroke-width` scales linearly with `sharedEvents` (min 1px, max ~6px), normalised across the edge set.
- Stroke colour: muted ink-family tone to avoid visual noise.

**Nodes**
- Rendered as `<g>` elements, each containing:
  - `<image>` clipped to the circular `<clipPath>` — loads from `avatarUrl`. On error falls back to hiding the image; the circle background colour (derived from `displayName` via the same `stringToColor` logic as `Avatar`) remains visible.
  - `<circle>` for the border (stroke `border-ink`, fill transparent or background colour when no image).
  - `<text>` label below the node (display name, truncated if long).
- Node radius scales with `Math.sqrt(eventCount)`, normalised so the most-active user has radius ~30px and minimum is ~12px.

**Force simulation**
- Forces: `forceLink` (edges, distance ~120), `forceManyBody` (repulsion, strength ~-200), `forceCenter` (SVG midpoint), `forceCollide` (radius = node radius + 4px to prevent overlap).
- All nodes initialised at `(width/2, height/2)` before the simulation starts, so they all burst outward from the centre as alpha decays — the bubble-pop entry effect.
- On each tick d3 updates `<line>` x1/y1/x2/y2 and `<g>` `transform="translate(x,y)"` directly on DOM elements via d3 selections (no React state involved).
- Simulation is stopped and SVG cleared (`d3.select(ref).selectAll("*").remove()`) before reinitialising when `graph` prop changes.

**Interaction**
- Nodes are draggable via d3's standard drag behaviour (fixes `fx`/`fy` on drag, releases on drag end).
- Hover tooltip: shows `displayName` and event count (implemented as a `<title>` element on the `<g>` for simplicity).

**Entry animation**
- Nodes start at the centre with `fx`/`fy` pinned, then released on the first tick so the simulation drives them outward.
- Each node `<g>` receives a CSS class that transitions `opacity` from 0→1 over ~300ms triggered on mount, staggered slightly by index.

### `Rewind.tsx` change

The "always together" `<Slab>` block (currently rendering `topSocialPairs` as a `<ul>`) is replaced with:

```tsx
{data.socialGraph && (
  <Slab className="p-5">
    <span className="text-[11px] font-extrabold tracking-[0.18em] text-mute uppercase">
      social graph
    </span>
    <SocialGraph graph={data.socialGraph} />
  </Slab>
)}
```

The section is only shown in guild scope (personal scope returns `socialGraph: null`).

### Dependency

`d3` is added to `frontend/package.json`. The full package is used for simplicity.

---

## What is not in scope

- Personal-scope graph (deferred — requires a separate `/rewind/graph/me` endpoint or enriched personal-mode response)
- Cross-guild graph endpoint (noted by user as a future addition)
- Minimum edge-weight filter (currently set to no practical limit; filtering UI can be added later)
- Mobile-specific graph layout adjustments
