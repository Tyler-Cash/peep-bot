"use client";

import { useEffect, useState } from "react";
import { Chunky } from "@/components/ui/Chunky";
import {
  replayLifecycleEvent,
  useAdminGuildEvents,
  type AdminEvent,
  type AdminGuild,
} from "@/lib/hooks";
import { LIFECYCLE_STAGES, type LifecycleStage, catFor } from "./lifecycle";
import clsx from "@/lib/clsx";

type ReplayState =
  | { phase: "idle" }
  | { phase: "running" }
  | { phase: "done"; message: string; listeners: string[] }
  | { phase: "error"; message: string };

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

  useEffect(() => {
    if (open) {
      setEventId(prefillEvent?.id ?? "");
      setStageId(prefillStage?.id ?? "");
      setState({ phase: "idle" });
    }
  }, [open, prefillEvent, prefillStage]);

  if (!open) return null;

  const stage = LIFECYCLE_STAGES.find((s) => s.id === stageId);
  const ev = (events ?? []).find((e) => e.id === eventId);
  const ready = !!eventId && !!stageId;

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
      setState({
        phase: "error",
        message: e instanceof Error ? e.message : "replay failed",
      });
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-start justify-center p-4 sm:p-12 overflow-y-auto">
      <div
        className="absolute inset-0 bg-ink/40 backdrop-blur-sm"
        onClick={onClose}
        aria-hidden
      />
      <div className="relative z-10 w-full max-w-[680px] rounded-hero border-[1.5px] border-ink bg-white shadow-hero overflow-hidden">
        <div className="px-5 py-4 border-b-[1.5px] border-ink bg-[#FFF0A6] flex items-end justify-between gap-3">
          <div>
            <span className="eyebrow">replay console</span>
            <p className="mt-0.5 text-[26px] sm:text-[28px] font-extrabold tracking-[-0.03em]">
              ↻ rerun a lifecycle stage
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
            <div>
              <label className="block mb-1.5 eyebrow">event</label>
              <select
                value={eventId}
                onChange={(e) => setEventId(e.target.value)}
                className="w-full px-3 py-2.5 border-[1.5px] border-ink rounded-chip bg-paper text-[14px] font-bold"
              >
                <option value="">— pick an event —</option>
                {(events ?? []).map((e) => {
                  const m = catFor(e.category);
                  return (
                    <option key={e.id} value={e.id}>
                      {m.emoji} {e.name} · #{e.id.slice(-6)}
                    </option>
                  );
                })}
              </select>
            </div>

            <div>
              <label className="block mb-1.5 eyebrow">stage / operation</label>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
                {LIFECYCLE_STAGES.filter((s) => s.kind === "transition" || s.kind === "side").map(
                  (s) => {
                    const a = stageId === s.id;
                    return (
                      <button
                        key={s.id}
                        type="button"
                        onClick={() => setStageId(s.id)}
                        className={clsx(
                          "flex flex-col items-start gap-0.5 px-3 py-2.5 rounded-chip border-[1.5px] border-ink text-left",
                          a
                            ? "bg-leaf shadow-rest"
                            : "bg-white hover:bg-paper2",
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
                  },
                )}
              </div>
            </div>

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

            <div className="px-3.5 py-3 border-[1.5px] border-ink rounded-chip bg-paper2">
              <span className="eyebrow">what will happen</span>
              {ev && stage ? (
                <p className="mt-1 text-[14px] text-ink2 font-semibold leading-snug">
                  re-publishes{" "}
                  <code className="font-mono font-extrabold">{stage.trigger}</code> for{" "}
                  <strong>{ev.name}</strong>. {stage.listener} runs again.{" "}
                  {stage.state ? (
                    <>
                      event state moves to{" "}
                      <code className="font-mono font-extrabold">{stage.state}</code>.
                    </>
                  ) : (
                    <>state stays where it is (side-effect listener).</>
                  )}
                </p>
              ) : (
                <p className="mt-1 text-[14px] text-mute font-semibold">
                  pick an event and a stage.
                </p>
              )}
            </div>

            {state.phase === "error" && (
              <div className="px-3 py-2 border-[1.5px] border-[#991B1B] rounded-chip bg-[#FFE5E5] text-[#991B1B] text-[13px] font-bold">
                {state.message}
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
                disabled={!ready || state.phase === "running"}
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
