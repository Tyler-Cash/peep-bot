"use client";

import { forwardRef, useEffect, useMemo, useRef, useState } from "react";
import { Chunky } from "@/components/ui/Chunky";
import {
  replayLifecycleEvent,
  useAdminGuildEvents,
  type AdminEvent,
  type AdminGuild,
} from "@/lib/hooks";
import {
  IDEMPOTENCY_CHIP,
  IDEMPOTENCY_LABEL,
  LIFECYCLE_STAGES,
  STAGE_BY_ID,
  catFor,
  coListenersForTrigger,
  stageIdForState,
  type LifecycleStage,
} from "./lifecycle";
import type { AdminEventHistoryEntry } from "@/lib/hooks";
import { describeError, type ErrorRef as ErrorRefInfo } from "@/lib/api";
import { ErrorRef } from "@/components/ui/ErrorRef";
import clsx from "@/lib/clsx";

type ReplayState =
  | { phase: "idle" }
  | { phase: "running" }
  | { phase: "done"; message: string; listeners: string[] }
  | { phase: "error"; message: string; info: ErrorRefInfo | null };

const REPLAYABLE_STAGES = LIFECYCLE_STAGES.filter(
  (s) => s.kind === "transition" || s.kind === "side",
);

function eventIsStuck(ev: AdminEvent): LifecycleStage | null {
  const last = ev.history.at(-1);
  if (last && !last.ok) return STAGE_BY_ID[last.stage] ?? null;
  return null;
}

function suggestedStageFor(ev: AdminEvent | null): LifecycleStage | null {
  if (!ev) return null;
  const stuck = eventIsStuck(ev);
  if (stuck) return stuck;
  return STAGE_BY_ID[stageIdForState(ev.state)] ?? null;
}

export function ReplayModal({
  open,
  onClose,
  prefillEvent,
  prefillStage,
  activeGuild,
}: {
  open: boolean;
  onClose: () => void;
  prefillEvent: AdminEvent | null;
  prefillStage: LifecycleStage | null;
  activeGuild: AdminGuild | null;
}) {
  const { data: events } = useAdminGuildEvents(open ? activeGuild?.guildId : null);
  const [eventId, setEventId] = useState<string>("");
  const [stageId, setStageId] = useState<string>("");
  const [actor, setActor] = useState("you (admin)");
  const [skipSideEffects, setSkipSideEffects] = useState(false);
  const [state, setState] = useState<ReplayState>({ phase: "idle" });
  const [pickerOpen, setPickerOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [showAllStages, setShowAllStages] = useState(false);
  const [stuckOnly, setStuckOnly] = useState(false);
  const searchRef = useRef<HTMLInputElement>(null);

  const ev = useMemo(
    () => (events ?? []).find((e) => e.id === eventId) ?? null,
    [events, eventId],
  );
  const stage = LIFECYCLE_STAGES.find((s) => s.id === stageId) ?? null;
  const suggested = suggestedStageFor(ev);

  useEffect(() => {
    if (!open) return;
    setEventId(prefillEvent?.id ?? "");
    setState({ phase: "idle" });
    setQuery("");
    setShowAllStages(false);
    setStuckOnly(false);
    // When opening without a prefilled event, drop straight into the picker so the
    // search input is the first thing the admin sees — no extra click to start typing.
    setPickerOpen(!prefillEvent);
    // Pre-select the most likely stage: explicit prefill > the event's stuck/current stage.
    if (prefillStage) {
      setStageId(prefillStage.id);
    } else {
      const s = suggestedStageFor(prefillEvent);
      setStageId(s?.id ?? "");
    }
  }, [open, prefillEvent, prefillStage]);

  useEffect(() => {
    if (pickerOpen) {
      // Focus the search box on next paint so it's typeable immediately.
      const t = setTimeout(() => searchRef.current?.focus(), 0);
      return () => clearTimeout(t);
    }
  }, [pickerOpen]);

  const allEvents = useMemo(() => events ?? [], [events]);
  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    let list = allEvents;
    if (stuckOnly) list = list.filter((e) => !!eventIsStuck(e));
    if (q) {
      list = list.filter((e) => {
        const blob = `${e.name} ${e.id} ${e.state} ${e.category} ${e.when ?? ""}`.toLowerCase();
        return blob.includes(q);
      });
    }
    // Stuck first, then upcoming/recent. Capped to 50 rows so the list stays scrollable
    // but predictable even on guilds with thousands of events.
    const sorted = [...list].sort((a, b) => {
      const sa = eventIsStuck(a) ? 0 : 1;
      const sb = eventIsStuck(b) ? 0 : 1;
      if (sa !== sb) return sa - sb;
      return (b.when ?? b.createdAt ?? "").localeCompare(a.when ?? a.createdAt ?? "");
    });
    return sorted.slice(0, 50);
  }, [allEvents, query, stuckOnly]);

  const stuckCount = allEvents.filter((e) => !!eventIsStuck(e)).length;
  const ready = !!eventId && !!stageId;

  if (!open) return null;

  async function run() {
    if (!stage || !eventId) return;
    setState({ phase: "running" });
    try {
      const result = await replayLifecycleEvent({
        eventId,
        lifecycleEventType: stage.trigger,
        skipSideEffects,
      });
      setState({ phase: "done", message: result.message, listeners: result.listeners });
    } catch (e) {
      const { message, ref } = describeError(e);
      setState({ phase: "error", message, info: ref });
    }
  }

  const stuckStage = ev ? eventIsStuck(ev) : null;

  return (
    <div className="fixed inset-0 z-50 flex items-start justify-center p-4 sm:p-12 overflow-y-auto">
      <div
        className="absolute inset-0 bg-ink/40 backdrop-blur-sm"
        onClick={onClose}
        aria-hidden
      />
      <div className="relative z-10 w-full max-w-[720px] rounded-hero border-[1.5px] border-ink bg-white shadow-hero overflow-hidden">
        <div className="px-5 py-4 border-b-[1.5px] border-ink bg-[#FFF0A6] flex items-end justify-between gap-3">
          <div>
            <span className="eyebrow">replay console</span>
            <p className="mt-0.5 text-[26px] sm:text-[28px] font-extrabold tracking-[-0.03em]">
              {ev ? <>↻ replay <span className="italic">{ev.name}</span></> : "↻ pick an event to replay"}
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="bg-transparent border-0 text-[20px] font-extrabold cursor-pointer"
            aria-label="close replay"
          >
            ✕
          </button>
        </div>

        {state.phase === "idle" || state.phase === "running" || state.phase === "error" ? (
          <div className="p-5 flex flex-col gap-4">
            {/* EVENT — either the picker or the chosen-event hero card */}
            <div>
              <div className="flex items-baseline justify-between mb-1.5">
                <label className="eyebrow">event</label>
                {ev && !pickerOpen && (
                  <button
                    type="button"
                    onClick={() => {
                      setPickerOpen(true);
                      setQuery("");
                    }}
                    className="text-[12px] font-extrabold tracking-[-0.01em] text-ink underline underline-offset-2 hover:text-leafDk bg-transparent border-0 cursor-pointer"
                  >
                    change event
                  </button>
                )}
              </div>

              {ev && !pickerOpen ? (
                <EventHero ev={ev} stuckStage={stuckStage} />
              ) : (
                <EventPicker
                  ref={searchRef}
                  query={query}
                  onQueryChange={setQuery}
                  filtered={filtered}
                  totalCount={allEvents.length}
                  stuckCount={stuckCount}
                  stuckOnly={stuckOnly}
                  onToggleStuckOnly={() => setStuckOnly((v) => !v)}
                  selectedId={eventId}
                  onSelect={(id) => {
                    setEventId(id);
                    setPickerOpen(false);
                    const next = (events ?? []).find((e) => e.id === id) ?? null;
                    const s = suggestedStageFor(next);
                    if (s) setStageId(s.id);
                  }}
                  onCancel={ev ? () => setPickerOpen(false) : undefined}
                />
              )}
            </div>

            {/* STAGE — suggested first, "all stages" collapsible */}
            {ev && !pickerOpen && (
              <div>
                <label className="block mb-1.5 eyebrow">stage to replay</label>
                {suggested && (
                  <SuggestedStageCard
                    stage={suggested}
                    selected={stageId === suggested.id}
                    reason={stuckStage ? "stuck here" : "current stage"}
                    onSelect={() => setStageId(suggested.id)}
                  />
                )}
                <button
                  type="button"
                  onClick={() => setShowAllStages((v) => !v)}
                  className="mt-2 text-[12px] font-extrabold tracking-[-0.01em] text-ink underline underline-offset-2 hover:text-leafDk bg-transparent border-0 cursor-pointer"
                >
                  {showAllStages ? "hide other stages" : `or pick another stage (${REPLAYABLE_STAGES.length})`}
                </button>
                {showAllStages && (
                  <div className="mt-2 grid grid-cols-1 sm:grid-cols-2 gap-2">
                    {REPLAYABLE_STAGES.filter((s) => s.id !== suggested?.id).map((s) => {
                      const a = stageId === s.id;
                      return (
                        <button
                          key={s.id}
                          type="button"
                          onClick={() => setStageId(s.id)}
                          className={clsx(
                            "flex flex-col items-start gap-0.5 px-3 py-2.5 rounded-chip border-[1.5px] border-ink text-left",
                            a ? "bg-leaf shadow-rest" : "bg-white hover:bg-paper2",
                          )}
                        >
                          <span className="text-[14px] font-extrabold tracking-[-0.01em]">
                            {s.emoji} {s.label}
                          </span>
                          <span className="text-[11px] font-bold text-mute font-mono">
                            {s.listener} · {s.state ?? "side-effect"}
                          </span>
                        </button>
                      );
                    })}
                  </div>
                )}
              </div>
            )}

            {ev && !pickerOpen && (
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <div>
                  <label className="block mb-1.5 eyebrow">running as</label>
                  <input
                    value={actor}
                    onChange={(e) => setActor(e.target.value)}
                    className="w-full px-3 py-2.5 border-[1.5px] border-ink rounded-chip bg-paper text-[14px] font-bold box-border"
                  />
                </div>
                <label
                  className={clsx(
                    "flex items-center gap-2.5 px-3 py-2.5 border-[1.5px] border-ink rounded-chip cursor-pointer",
                    skipSideEffects ? "bg-[#FFF0A6]" : "bg-paper",
                  )}
                >
                  <input
                    type="checkbox"
                    checked={skipSideEffects}
                    onChange={(e) => setSkipSideEffects(e.target.checked)}
                    className="accent-ink"
                  />
                  <span className="text-[13px] font-bold">skip side-effects (album, dms)</span>
                </label>
              </div>
            )}

            {ev && !pickerOpen && (
              stage ? (
                <WhatWillHappen stage={stage} ev={ev} />
              ) : (
                <div className="px-3.5 py-3 border-[1.5px] border-ink rounded-chip bg-paper2">
                  <span className="eyebrow">what will happen</span>
                  <p className="mt-1 text-[14px] text-mute font-semibold">pick a stage above.</p>
                </div>
              )
            )}

            {state.phase === "error" && (
              <div className="px-3 py-2 border-[1.5px] border-[#991B1B] rounded-chip bg-[#FFE5E5] text-[#991B1B] text-[13px] font-bold">
                {state.message}
                <ErrorRef info={state.info} />
              </div>
            )}

            <div className="flex justify-between items-center gap-3 mt-1">
              <button
                type="button"
                onClick={onClose}
                className="bg-transparent border-0 text-[14px] font-extrabold text-mute hover:text-ink"
              >
                cancel
              </button>
              <Chunky
                variant="leaf"
                onClick={run}
                disabled={!ready || state.phase === "running" || pickerOpen}
              >
                {state.phase === "running" ? "queueing…" : "↻ replay stage"}
              </Chunky>
            </div>
          </div>
        ) : (
          <div className="p-5 flex flex-col gap-3.5">
            <div className="px-4 py-3 border-[1.5px] border-ink rounded-chip bg-[#B8E89A] text-[#1F4410]">
              <span className="eyebrow text-[#1F4410]">queued</span>
              <p className="mt-1 text-[17px] font-extrabold tracking-[-0.01em]">
                ✓ {state.message}
              </p>
            </div>
            <pre className="m-0 px-3.5 py-3 border-[1.5px] border-ink rounded-chip bg-ink text-[#B8E89A] font-mono text-[12px] leading-snug overflow-x-auto whitespace-pre-wrap">
{`[admin] replay requested by ${actor}
[admin] event=${eventId} stage=${stage?.id} skipSideEffects=${skipSideEffects}
[bus]   publish ${stage?.trigger}(eventId=${eventId})
${state.listeners.map((l) => `[outbox] queued ${l}`).join("\n")}
[wait]  listener pipeline runs after commit (≤ 5s)`}
            </pre>
            {skipSideEffects && (
              <p className="text-[12px] text-mute font-semibold">
                note: skip-side-effects is advisory only in this version — every registered listener
                will run.
              </p>
            )}
            <div className="flex justify-end gap-2.5">
              <Chunky
                variant="paper"
                size="sm"
                onClick={() => setState({ phase: "idle" })}
              >
                replay another
              </Chunky>
              <Chunky variant="leaf" size="sm" onClick={onClose}>
                done
              </Chunky>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

function WhatWillHappen({ stage, ev }: { stage: LifecycleStage; ev: AdminEvent }) {
  // Replay republishes the trigger event — every listener subscribed to it will fire, not just
  // the one named by the picked stage. Surface that fan-out explicitly so the admin isn't
  // surprised by, e.g., a TfNSW post happening when they only wanted to re-init the channel.
  const coListeners = coListenersForTrigger(stage.trigger, stage.id);
  // History rows for this stage's trigger. The backend groups by lifecycle event type so each
  // entry rolls up all listeners on the trigger — we present it that way (most-recent first).
  const triggerHistory = ev.history
    .filter((h) => h.lifecycleEventType === stage.trigger)
    .slice()
    .reverse();

  return (
    <div className="px-3.5 py-3 border-[1.5px] border-ink rounded-chip bg-paper2 flex flex-col gap-2.5">
      <div className="flex items-center gap-2 flex-wrap">
        <span className="eyebrow">what will happen</span>
        <span
          className={clsx(
            "inline-block text-[10px] font-extrabold tracking-[0.08em] uppercase px-1.5 py-px rounded-md border-[1.5px]",
            IDEMPOTENCY_CHIP[stage.idempotency],
          )}
        >
          {IDEMPOTENCY_LABEL[stage.idempotency]}
        </span>
      </div>

      <p className="text-[14px] text-ink2 font-semibold leading-snug">
        {stage.humanEffect}
      </p>

      {stage.dedup && (
        <p className="text-[12.5px] text-ink2 font-medium leading-snug">
          <span className="font-extrabold">dedup:</span> {stage.dedup}
        </p>
      )}

      <p className="text-[12.5px] text-mute font-mono leading-snug">
        publishes <span className="font-extrabold text-ink">{stage.trigger}</span> · listener{" "}
        <span className="font-extrabold text-ink">{stage.listener}</span>
        {stage.state ? (
          <> · state → <span className="font-extrabold text-ink">{stage.state}</span></>
        ) : (
          <> · no state change</>
        )}
      </p>

      {coListeners.length > 0 && (
        <div className="border-t-[1.5px] border-ink/15 pt-2.5">
          <span className="eyebrow">also re-runs on this trigger</span>
          <ul className="mt-1 flex flex-col gap-1">
            {coListeners.map((c) => (
              <li
                key={c.id}
                className="text-[12.5px] font-semibold text-ink2 leading-snug flex items-start gap-1.5"
              >
                <span aria-hidden>{c.emoji}</span>
                <span>
                  <span className="font-extrabold text-ink">{c.label}</span>{" "}
                  <span className="text-mute font-mono">({c.listener})</span> — {c.humanEffect}
                </span>
              </li>
            ))}
          </ul>
          <p className="mt-1.5 text-[11.5px] text-mute font-medium italic leading-snug">
            replay fans out to every listener on <code className="font-mono">{stage.trigger}</code>{" "}
            — skip-side-effects below is advisory only.
          </p>
        </div>
      )}

      <div className="border-t-[1.5px] border-ink/15 pt-2.5">
        <span className="eyebrow">prior attempts on this event</span>
        {triggerHistory.length === 0 ? (
          <p className="mt-1 text-[12.5px] text-mute font-semibold">
            never run for this event — will be the first attempt.
          </p>
        ) : (
          <ul className="mt-1 flex flex-col gap-1">
            {triggerHistory.slice(0, 4).map((h, i) => (
              <HistoryRow key={`${h.ts}-${i}`} h={h} />
            ))}
            {triggerHistory.length > 4 && (
              <li className="text-[11.5px] text-mute font-medium">
                + {triggerHistory.length - 4} older
              </li>
            )}
          </ul>
        )}
      </div>
    </div>
  );
}

function HistoryRow({ h }: { h: AdminEventHistoryEntry }) {
  return (
    <li
      className={clsx(
        "text-[12.5px] font-semibold leading-snug grid grid-cols-[auto_1fr_auto] items-baseline gap-2 px-2 py-1 rounded-md border-[1px]",
        h.ok
          ? "bg-leafLt/40 border-[#1F4410]/30 text-ink2"
          : "bg-[#FFE5E5] border-[#7A1A1A]/40 text-[#7A1A1A]",
      )}
    >
      <span className="font-extrabold">{h.ok ? "✓" : "⚠"}</span>
      <span className="min-w-0">
        <span className="font-mono">{h.ts}</span>
        <span className="ml-2 text-mute">
          {h.attempts} attempt{h.attempts === 1 ? "" : "s"} ·{" "}
          <span className="font-extrabold">{h.listenerName}</span>
        </span>
        {!h.ok && h.detail && (
          <div className="font-medium whitespace-normal mt-0.5">{h.detail}</div>
        )}
      </span>
    </li>
  );
}

function EventHero({
  ev,
  stuckStage,
}: {
  ev: AdminEvent;
  stuckStage: LifecycleStage | null;
}) {
  const m = catFor(ev.category);
  return (
    <div
      className={clsx(
        "px-3.5 py-3 border-[1.5px] border-ink rounded-chip flex items-start gap-3",
        stuckStage ? "bg-[#FFE5E5]" : "bg-leafLt/60",
      )}
    >
      <span className="text-[28px] leading-none mt-0.5">{m.emoji}</span>
      <div className="min-w-0 flex-1">
        <div className="text-[16px] font-extrabold tracking-[-0.01em] truncate">{ev.name}</div>
        <div className="text-[12px] font-bold text-mute font-mono truncate">
          #{ev.id.slice(-8)} · state=<span className="text-ink">{ev.state}</span>
          {ev.when ? <> · {ev.when}</> : null}
        </div>
        {stuckStage && (
          <div className="mt-1 text-[12px] font-extrabold text-[#7A1A1A] tracking-[-0.01em]">
            ⚠ stuck on {stuckStage.emoji} {stuckStage.label} — last attempt failed
          </div>
        )}
      </div>
    </div>
  );
}

function SuggestedStageCard({
  stage,
  selected,
  reason,
  onSelect,
}: {
  stage: LifecycleStage;
  selected: boolean;
  reason: string;
  onSelect: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onSelect}
      className={clsx(
        "w-full flex items-center gap-3 px-3.5 py-3 rounded-chip border-[1.5px] border-ink text-left",
        selected ? "bg-leaf shadow-rest" : "bg-white hover:bg-paper2",
      )}
    >
      <span className="text-[22px] leading-none">{stage.emoji}</span>
      <div className="flex-1 min-w-0">
        <div className="text-[15px] font-extrabold tracking-[-0.01em]">
          {stage.label}
          <span className="ml-2 align-middle inline-block text-[10px] font-extrabold tracking-[0.12em] uppercase px-1.5 py-px rounded-md border-[1.5px] border-ink bg-[#FFF0A6] text-ink">
            {reason}
          </span>
        </div>
        <div className="text-[11.5px] font-bold text-mute font-mono truncate">
          {stage.listener} · {stage.state ?? "side-effect"} · trigger={stage.trigger}
        </div>
      </div>
      <span className="text-[18px] font-extrabold">{selected ? "●" : "○"}</span>
    </button>
  );
}

type EventPickerProps = {
  query: string;
  onQueryChange: (q: string) => void;
  filtered: AdminEvent[];
  totalCount: number;
  stuckCount: number;
  stuckOnly: boolean;
  onToggleStuckOnly: () => void;
  selectedId: string;
  onSelect: (id: string) => void;
  onCancel?: () => void;
};

const EventPicker = forwardRef<HTMLInputElement, EventPickerProps>(function EventPicker(
  {
    query,
    onQueryChange,
    filtered,
    totalCount,
    stuckCount,
    stuckOnly,
    onToggleStuckOnly,
    selectedId,
    onSelect,
    onCancel,
  },
  ref,
) {
  return (
      <div className="rounded-chip border-[1.5px] border-ink bg-white overflow-hidden">
        <div className="flex items-center gap-2 px-3 py-2 border-b-[1.5px] border-ink/15 bg-paper">
          <span className="text-[14px] font-extrabold text-mute">🔎</span>
          <input
            ref={ref}
            value={query}
            onChange={(e) => onQueryChange(e.target.value)}
            placeholder={`search ${totalCount} events by name, state, id…`}
            className="flex-1 bg-transparent border-0 outline-none text-[14px] font-bold placeholder:text-mute"
          />
          {stuckCount > 0 && (
            <button
              type="button"
              onClick={onToggleStuckOnly}
              className={clsx(
                "text-[11px] font-extrabold tracking-[0.06em] uppercase px-2 py-0.5 rounded-md border-[1.5px]",
                stuckOnly
                  ? "bg-[#7A1A1A] text-white border-[#7A1A1A]"
                  : "bg-[#FFE5E5] text-[#7A1A1A] border-[#7A1A1A]",
              )}
            >
              {stuckOnly ? "× stuck only" : `${stuckCount} stuck`}
            </button>
          )}
          {onCancel && (
            <button
              type="button"
              onClick={onCancel}
              className="text-[12px] font-extrabold text-mute hover:text-ink bg-transparent border-0 cursor-pointer"
            >
              cancel
            </button>
          )}
        </div>
        <div className="max-h-[280px] overflow-y-auto">
          {filtered.length === 0 ? (
            <div className="px-3 py-6 text-center text-[13px] text-mute font-semibold">
              {query || stuckOnly ? "no events match" : "no events in this guild"}
            </div>
          ) : (
            filtered.map((e) => {
              const m = catFor(e.category);
              const stuck = eventIsStuck(e);
              const isActive = selectedId === e.id;
              return (
                <button
                  key={e.id}
                  type="button"
                  onClick={() => onSelect(e.id)}
                  className={clsx(
                    "w-full grid grid-cols-[auto_1fr_auto] gap-2.5 items-center px-3 py-2 text-left border-b-[1px] border-ink/10 last:border-b-0",
                    isActive ? "bg-leafLt/60" : "hover:bg-paper2",
                  )}
                >
                  <span className="text-[18px] leading-none">{m.emoji}</span>
                  <div className="min-w-0">
                    <div className="text-[14px] font-extrabold tracking-[-0.01em] truncate">
                      {e.name}
                    </div>
                    <div className="text-[11.5px] font-bold text-mute font-mono truncate">
                      #{e.id.slice(-8)} · {e.state}{e.when ? ` · ${e.when}` : ""}
                    </div>
                  </div>
                  {stuck ? (
                    <span className="text-[10px] font-extrabold tracking-[0.06em] uppercase px-1.5 py-px border-[1.5px] border-[#7A1A1A] rounded-md bg-[#FFE5E5] text-[#7A1A1A] whitespace-nowrap">
                      ⚠ stuck
                    </span>
                  ) : (
                    <span />
                  )}
                </button>
              );
            })
          )}
          {totalCount > filtered.length && (
            <div className="px-3 py-2 text-[11px] text-mute font-semibold border-t-[1px] border-ink/10">
              showing {filtered.length} of {totalCount} — refine the search to narrow further
            </div>
          )}
        </div>
      </div>
    );
  });
