"use client";

import { useState } from "react";
import { Slab } from "@/components/ui/Slab";
import { useAdminGuildEvents, type AdminEvent, type AdminGuild } from "@/lib/hooks";
import {
  catFor,
  LIFECYCLE_STAGES,
  PIPELINE_LIVE_STATE_IDS,
  PIPELINE_TERMINAL_STATE_IDS,
  stageIdForState,
  STAGE_BY_ID,
  type LifecycleStage,
} from "./lifecycle";
import clsx from "@/lib/clsx";

export type EventsViz = "pipeline" | "state";

export function EventsSection({
  activeGuild,
  onOpenReplay,
}: {
  activeGuild: AdminGuild | null;
  onOpenReplay: (event: AdminEvent | null, stage: LifecycleStage | null) => void;
}) {
  const [viz, setViz] = useState<EventsViz>("pipeline");
  const { data: events } = useAdminGuildEvents(activeGuild?.guildId);
  const list = events ?? [];
  const failingCount = list.filter(isStuck).length;

  return (
    <div className="max-w-[1280px] mx-auto px-5 pt-6 pb-16">
      <div className="flex items-end justify-between gap-4 flex-wrap mb-4">
        <div>
          <span className="eyebrow">events · {activeGuild?.name ?? "—"}</span>
          <h1 className="mt-1 text-[40px] sm:text-[48px] font-extrabold tracking-[-0.04em] lowercase leading-none">
            the flow.
          </h1>
          <p className="mt-2 text-[16px] text-ink2 font-medium">
            {list.length} events · {failingCount} stuck on a failing stage. click any stage to replay it.
          </p>
        </div>
        <VizToggle value={viz} onChange={setViz} />
      </div>

      <Slab className="p-0 overflow-hidden">
        {viz === "pipeline" && (
          <>
            <div className="px-4 py-3.5 flex items-center justify-between gap-3 flex-wrap">
              <div>
                <span className="eyebrow">pipeline</span>
                <p className="mt-0.5 text-[18px] font-extrabold tracking-[-0.02em]">
                  where every event is, right now
                </p>
              </div>
              <span className="text-[12px] font-bold text-mute inline-flex items-center gap-2">
                <span className="inline-block w-3 h-3 rounded-sm bg-[#FFE5E5] border-[1.5px] border-[#7A1A1A]" />
                stuck — needs a retry
              </span>
            </div>
            <PipelineView events={list} onOpenReplay={onOpenReplay} />
          </>
        )}
        {viz === "state" && <StateMachineView events={list} />}
      </Slab>
    </div>
  );
}

function VizToggle({
  value,
  onChange,
}: {
  value: EventsViz;
  onChange: (v: EventsViz) => void;
}) {
  const opts: Array<{ id: EventsViz; label: string }> = [
    { id: "pipeline", label: "≡ pipeline" },
    { id: "state", label: "◇ state" },
  ];
  return (
    <div className="flex gap-1.5 p-1 rounded-card border-[1.5px] border-ink bg-white shadow-rest">
      {opts.map((o) => {
        const a = value === o.id;
        return (
          <button
            key={o.id}
            type="button"
            onClick={() => onChange(o.id)}
            className={clsx(
              "px-3 py-1.5 rounded-md text-[13px] font-extrabold tracking-[-0.01em] whitespace-nowrap border-[1.5px]",
              a
                ? "bg-ink text-paper border-ink"
                : "bg-transparent text-ink border-transparent hover:bg-paper2",
            )}
          >
            {o.label}
          </button>
        );
      })}
    </div>
  );
}

function isStuck(ev: AdminEvent): boolean {
  const last = ev.history.at(-1);
  return !!last && !last.ok;
}
function lastFailedStage(ev: AdminEvent): LifecycleStage | null {
  const last = ev.history.at(-1);
  if (!last) return null;
  return STAGE_BY_ID[last.stage] ?? null;
}
function stuckDetail(ev: AdminEvent): string {
  const last = ev.history.at(-1);
  return last?.detail ?? "stage failed";
}

function PipelineView({
  events,
  onOpenReplay,
}: {
  events: AdminEvent[];
  onOpenReplay: (e: AdminEvent | null, s: LifecycleStage | null) => void;
}) {
  const liveStages = PIPELINE_LIVE_STATE_IDS.map((id) =>
    LIFECYCLE_STAGES.find((s) => s.id === id),
  ).filter((s): s is LifecycleStage => !!s);
  const termStages = PIPELINE_TERMINAL_STATE_IDS.map((id) =>
    LIFECYCLE_STAGES.find((s) => s.id === id),
  ).filter((s): s is LifecycleStage => !!s);

  const eventsForStage = (id: string) =>
    events.filter((e) => stageIdForState(e.state) === id);

  return (
    <div>
      <div className="flex border-t-[1.5px] border-ink items-stretch overflow-x-auto">
        {liveStages.map((s) => (
          <PipelineColumn
            key={s.id}
            stage={s}
            events={eventsForStage(s.id)}
            onOpenReplay={onOpenReplay}
          />
        ))}
      </div>
      <div className="flex border-t-[1.5px] border-dashed border-ink/25 bg-ink/[0.02] items-stretch overflow-x-auto">
        {termStages.map((s) => (
          <PipelineColumn
            key={s.id}
            stage={s}
            events={eventsForStage(s.id)}
            onOpenReplay={onOpenReplay}
            demoted
            capped
          />
        ))}
      </div>
    </div>
  );
}

function PipelineColumn({
  stage,
  events,
  onOpenReplay,
  demoted = false,
  capped = false,
}: {
  stage: LifecycleStage;
  events: AdminEvent[];
  onOpenReplay: (e: AdminEvent | null, s: LifecycleStage | null) => void;
  demoted?: boolean;
  capped?: boolean;
}) {
  const TERMINAL_CAP = 3;
  const stuckCount = events.filter(isStuck).length;
  const sorted = demoted
    ? [...events].sort((a, b) => {
        const ta = a.history.at(-1)?.ts ?? "";
        const tb = b.history.at(-1)?.ts ?? "";
        return tb.localeCompare(ta);
      })
    : events;
  const visible = capped ? sorted.slice(0, TERMINAL_CAP) : sorted;
  const hidden = sorted.length - visible.length;

  return (
    <div
      className={clsx(
        "flex flex-col min-w-[180px] flex-1 basis-0 border-r border-ink/10",
        demoted ? "p-2.5 opacity-[0.62]" : "py-3.5 px-3",
      )}
    >
      <div className="flex items-baseline gap-1.5 mb-2.5 pb-2 border-b border-ink/10">
        <span className="text-[11px] font-extrabold tracking-[0.08em] uppercase">
          {stage.emoji} {stage.label}
        </span>
        <span className="flex-1" />
        <span className="text-[13px] font-extrabold tabular-nums">{events.length}</span>
        {stuckCount > 0 && (
          <span className="text-[10px] font-extrabold tracking-[0.06em] uppercase text-[#7A1A1A] px-1.5 py-px border-[1.5px] border-[#7A1A1A] rounded-md bg-[#FFE5E5]">
            {stuckCount} stuck
          </span>
        )}
      </div>
      <div className="flex flex-col gap-0.5 min-h-[40px]">
        {visible.length === 0 && (
          <span className="text-[12px] text-ink/35 font-bold py-1">—</span>
        )}
        {visible.map((e) => (
          <PipelineRow key={e.id} ev={e} onOpenReplay={onOpenReplay} />
        ))}
        {hidden > 0 && (
          <span className="mt-1 py-1 text-[11px] font-bold text-ink/55">
            + {hidden} older
          </span>
        )}
      </div>
    </div>
  );
}

function PipelineRow({
  ev,
  onOpenReplay,
}: {
  ev: AdminEvent;
  onOpenReplay: (e: AdminEvent | null, s: LifecycleStage | null) => void;
}) {
  const m = catFor(ev.category);
  const last = ev.history.at(-1);
  const stuck = isStuck(ev);

  return (
    <button
      type="button"
      onClick={() =>
        onOpenReplay(
          ev,
          last ? STAGE_BY_ID[last.stage] ?? null : STAGE_BY_ID[stageIdForState(ev.state)] ?? null,
        )
      }
      className={clsx(
        "grid grid-cols-[auto_1fr_auto] gap-2 items-baseline text-left px-2.5 py-1.5 -mx-2.5 rounded-md transition-colors",
        stuck
          ? "bg-[#FFE5E5] text-[#7A1A1A]"
          : "hover:bg-ink/[0.04]",
      )}
    >
      <span className="text-[13px] opacity-70 leading-tight">{m.emoji}</span>
      <div className="min-w-0 leading-tight">
        <div className="text-[13.5px] font-bold tracking-[-0.005em] truncate">
          {ev.name}
        </div>
        <div className="text-[11.5px] font-semibold opacity-65 tabular-nums truncate">
          {stuck ? `⚠ stuck · ${last?.ts ?? ""}` : ev.when ?? "—"}
        </div>
        {stuck && (
          <div className="text-[11px] font-semibold mt-0.5 opacity-85 whitespace-normal">
            {stuckDetail(ev)}
          </div>
        )}
      </div>
      {stuck && (
        <button
          type="button"
          onClick={(e) => {
            e.stopPropagation();
            onOpenReplay(ev, lastFailedStage(ev));
          }}
          className="self-center px-2 py-0.5 text-[11px] font-extrabold tracking-[-0.005em] border-[1.5px] border-[#7A1A1A] rounded-md bg-white text-[#7A1A1A] whitespace-nowrap"
        >
          ↻ retry
        </button>
      )}
    </button>
  );
}

// State-machine SVG. A small static layout is intentionally hand-coded — building a layout engine
// for ~10 nodes would be more code than the literal coordinates and harder to tweak.
function StateMachineView({ events }: { events: AdminEvent[] }) {
  const NODES = [
    { id: "created", x: 85, y: 120, label: "created" },
    { id: "init-channel", x: 245, y: 120, label: "init channel" },
    { id: "init-roles", x: 405, y: 120, label: "init roles" },
    { id: "classify", x: 565, y: 120, label: "classify" },
    { id: "planned", x: 725, y: 120, label: "planned" },
    { id: "pre-notified", x: 885, y: 120, label: "pre-notified" },
    { id: "completed", x: 405, y: 320, label: "completed" },
    { id: "archived", x: 565, y: 320, label: "archived" },
    { id: "cancelled", x: 725, y: 320, label: "cancelled" },
  ] as const;

  const edges: Array<[string, string, boolean]> = [
    ["created", "init-channel", false],
    ["init-channel", "init-roles", false],
    ["init-roles", "classify", false],
    ["classify", "planned", false],
    ["planned", "pre-notified", false],
    ["pre-notified", "completed", false],
    ["completed", "archived", false],
    ["planned", "cancelled", true],
    ["pre-notified", "cancelled", true],
  ];
  const counts: Record<string, number> = {};
  for (const n of NODES) counts[n.id] = 0;
  for (const e of events) {
    const stage = stageIdForState(e.state);
    if (stage in counts) counts[stage] = counts[stage] + 1;
  }
  const NW = 150;
  const NH = 64;

  return (
    <div className="p-4 overflow-x-auto">
      <svg
        viewBox="0 0 1080 420"
        width="100%"
        className="min-w-[1000px] block"
        style={{ fontFamily: "'Space Grotesk',sans-serif" }}
      >
        <defs>
          <marker
            id="adm-arrow"
            viewBox="0 0 10 10"
            refX="9"
            refY="5"
            markerWidth="8"
            markerHeight="8"
            orient="auto-start-reverse"
          >
            <path d="M0,0 L10,5 L0,10 z" fill="#0E100D" />
          </marker>
          <marker
            id="adm-arrow-r"
            viewBox="0 0 10 10"
            refX="9"
            refY="5"
            markerWidth="8"
            markerHeight="8"
            orient="auto-start-reverse"
          >
            <path d="M0,0 L10,5 L0,10 z" fill="#DC2626" />
          </marker>
        </defs>
        {edges.map(([a, b, isCancel]) => {
          const A = NODES.find((n) => n.id === a)!;
          const B = NODES.find((n) => n.id === b)!;
          const sameRow = A.y === B.y;
          let x1, y1, x2, y2;
          if (sameRow) {
            x1 = A.x + NW / 2;
            y1 = A.y;
            x2 = B.x - NW / 2;
            y2 = B.y;
          } else {
            x1 = A.x;
            y1 = A.y + NH / 2;
            x2 = B.x;
            y2 = B.y - NH / 2;
          }
          return (
            <line
              key={a + b}
              x1={x1}
              y1={y1}
              x2={x2}
              y2={y2}
              stroke={isCancel ? "#DC2626" : "#0E100D"}
              strokeWidth="2"
              strokeDasharray={isCancel ? "6 4" : "0"}
              markerEnd={isCancel ? "url(#adm-arrow-r)" : "url(#adm-arrow)"}
            />
          );
        })}
        {NODES.map((n) => {
          const count = counts[n.id] ?? 0;
          const isCancel = n.id === "cancelled";
          const stage = STAGE_BY_ID[n.id];
          return (
            <g
              key={n.id}
              transform={`translate(${n.x - NW / 2}, ${n.y - NH / 2})`}
            >
              <rect
                width={NW}
                height={NH}
                rx="10"
                ry="10"
                fill={isCancel ? "#0E100D" : "#fff"}
                stroke={isCancel ? "#991B1B" : "#0E100D"}
                strokeWidth="1.5"
                style={{ filter: "drop-shadow(3px 3px 0 #0E100D)" }}
              />
              <text
                x="14"
                y="26"
                fontSize="13"
                fontWeight="800"
                fill={isCancel ? "#F5F1E8" : "#0E100D"}
                style={{ textTransform: "uppercase", letterSpacing: "0.08em" }}
              >
                {stage?.emoji ?? "•"} {n.label}
              </text>
              <text
                x="14"
                y="48"
                fontSize="14"
                fontWeight="700"
                fill={isCancel ? "#F5F1E8" : "#6B6E66"}
              >
                {count} event{count === 1 ? "" : "s"}
              </text>
              {count > 0 && (
                <circle
                  cx={NW - 14}
                  cy="20"
                  r="9"
                  fill={isCancel ? "#DC2626" : "#7BC24F"}
                  stroke="#0E100D"
                  strokeWidth="1.5"
                />
              )}
            </g>
          );
        })}
      </svg>
      <p className="mt-3 text-[13px] text-mute font-semibold">
        nodes are <code className="font-mono font-extrabold">EventState</code>{" "}
        values. green dot = event currently parked here. dotted red edges = cancellation transitions.
      </p>
    </div>
  );
}
