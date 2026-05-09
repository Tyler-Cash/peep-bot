"use client";

import { Slab } from "@/components/ui/Slab";
import { StatusDot } from "./StatusDot";
import { catFor, stageIdForState, STAGE_BY_ID } from "./lifecycle";
import {
  useAdminActivity,
  useAdminGuildEvents,
  useAdminHealth,
  useAdminJobs,
  type AdminActivity,
  type AdminGuild,
} from "@/lib/hooks";

const HEALTH_LABELS: Record<string, string> = {
  bot: "bot",
  discord: "discord",
  database: "database",
  scheduler: "scheduler",
  listenerOutbox: "listener outbox",
};

export function OverviewSection({
  activeGuild,
  onJumpEvents,
  onOpenReplay,
}: {
  activeGuild: AdminGuild | null;
  onJumpEvents: () => void;
  onOpenReplay: () => void;
}) {
  const { data: health } = useAdminHealth();
  const { data: events } = useAdminGuildEvents(activeGuild?.guildId);
  const { data: activity } = useAdminActivity(activeGuild?.guildId);
  const { data: jobs } = useAdminJobs();

  const upcoming = (events ?? []).filter(
    (e) => !["POST_COMPLETED", "ARCHIVED", "CANCELLED", "DELETED"].includes(e.state),
  );
  const failures24h =
    activity?.filter((a) => a.kind === "fail").length ?? 0;
  const featuresOn = activeGuild
    ? [
        activeGuild.immichEnabled,
        activeGuild.googleAutocompleteEnabled,
        activeGuild.rewindEnabled,
        activeGuild.contractsEnabled,
      ].filter(Boolean).length
    : 0;
  const upcomingJobs = (jobs ?? [])
    .filter((j) => j.nextRun)
    .slice(0, 4);

  return (
    <div className="max-w-[1280px] mx-auto px-5 pt-6 pb-16">
      {/* Hero */}
      <div className="flex items-end justify-between mb-5 gap-5 flex-wrap">
        <div>
          <span className="eyebrow">overview · {activeGuild?.name ?? "—"}</span>
          <h1 className="mt-1 text-[44px] sm:text-[52px] font-extrabold tracking-[-0.04em] lowercase leading-none">
            everything&apos;s mostly fine.
          </h1>
          <p className="mt-2 text-[16px] text-ink2 font-medium">
            {upcoming.length} upcoming events · {failures24h} failure
            {failures24h === 1 ? "" : "s"} in last 24h · {featuresOn}/4 features on.
          </p>
        </div>
        <span className="inline-flex items-center gap-2 rounded-chip border-[1.5px] border-ink bg-white px-3 py-1.5 shadow-rest text-[13px] font-bold">
          <span className="w-2 h-2 rounded-full bg-[#5FA838]" />
          last sync just now · live
        </span>
      </div>

      {/* Health row */}
      <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-5 gap-3.5 mb-5">
        {Object.entries(health?.components ?? {}).map(([key, comp]) => (
          <Slab key={key} className="p-3.5 min-w-0">
            <div className="flex items-center gap-1.5 min-w-0">
              <StatusDot status={comp.status} />
              <span className="eyebrow text-[11px] tracking-[0.12em] truncate">
                {HEALTH_LABELS[key] ?? key}
              </span>
            </div>
            <p className="mt-1.5 text-[13.5px] font-semibold text-ink2 leading-snug">
              {comp.detail}
            </p>
          </Slab>
        ))}
        {!health && (
          <Slab className="p-3.5 col-span-full">
            <span className="text-mute text-[14px]">loading health…</span>
          </Slab>
        )}
      </div>

      {/* Main grid */}
      <div className="grid grid-cols-1 lg:grid-cols-[1.5fr_1fr] gap-4">
        {/* Activity */}
        <Slab className="p-0 overflow-hidden">
          <div className="px-4 py-3.5 flex items-center justify-between gap-3 flex-wrap">
            <div className="min-w-0">
              <span className="eyebrow whitespace-nowrap">recent activity</span>
              <p className="mt-0.5 text-[20px] font-extrabold tracking-[-0.02em] whitespace-nowrap">
                last 24 hours
              </p>
            </div>
            <button
              type="button"
              onClick={onJumpEvents}
              className="bg-transparent border-0 text-[13px] font-extrabold text-mute hover:text-ink whitespace-nowrap"
            >
              open events →
            </button>
          </div>
          {(activity ?? []).slice(0, 9).map((a, i) => (
            <ActivityRow key={i} a={a} />
          ))}
          {activity && activity.length === 0 && (
            <div className="px-4 py-6 border-t-[1.5px] border-ink text-mute text-[14px]">
              nothing happened. that&apos;s fine.
            </div>
          )}
        </Slab>

        {/* Right column */}
        <div className="flex flex-col gap-4">
          <Slab className="p-4">
            <span className="eyebrow">upcoming events</span>
            <p className="mt-0.5 text-[20px] font-extrabold tracking-[-0.02em]">
              {upcoming.length} in flight
            </p>
            <div className="mt-3 flex flex-col gap-2">
              {upcoming.slice(0, 4).map((e) => {
                const m = catFor(e.category);
                const stage = STAGE_BY_ID[stageIdForState(e.state)];
                return (
                  <div
                    key={e.id}
                    className="flex items-center gap-2.5 px-2.5 py-2 rounded-chip border-[1.5px] border-ink"
                    style={{ background: m.bg, color: m.ink }}
                  >
                    <span className="text-[20px]">{m.emoji}</span>
                    <span className="flex flex-col min-w-0 flex-1">
                      <span className="text-[14px] font-extrabold tracking-[-0.01em] truncate">
                        {e.name}
                      </span>
                      <span className="text-[11.5px] font-bold opacity-85">
                        {e.when ?? "—"} · {e.going}✅ {e.maybe}🤔
                      </span>
                    </span>
                    <span
                      className="text-[10.5px] font-extrabold tracking-[0.1em] uppercase px-1.5 py-0.5 rounded-md border-[1.5px]"
                      style={{ borderColor: m.ink, background: "rgba(255,255,255,0.6)" }}
                    >
                      {stage?.label ?? e.state.toLowerCase()}
                    </span>
                  </div>
                );
              })}
              {upcoming.length === 0 && (
                <span className="text-[14px] text-mute">nothing upcoming.</span>
              )}
            </div>
          </Slab>

          <Slab className="p-4">
            <div className="flex items-center justify-between gap-2">
              <span className="eyebrow">scheduler · next runs</span>
              <button
                type="button"
                onClick={onOpenReplay}
                className="bg-transparent border-0 text-[12px] font-extrabold text-mute hover:text-ink"
              >
                replay…
              </button>
            </div>
            <div className="mt-3 flex flex-col gap-1.5">
              {upcomingJobs.map((j) => (
                <div
                  key={j.id}
                  className="grid grid-cols-[14px_1fr_auto] items-center gap-2.5 text-[13px]"
                >
                  <StatusDot status={j.lastStatus} size={11} />
                  <span className="font-bold truncate">{j.label}</span>
                  <span className="text-[12px] font-bold text-mute tabular-nums">
                    {j.nextRun ? formatRelativeTime(j.nextRun) : "—"}
                  </span>
                </div>
              ))}
              {upcomingJobs.length === 0 && (
                <span className="text-[13px] text-mute">no scheduled runs visible</span>
              )}
            </div>
          </Slab>
        </div>
      </div>
    </div>
  );
}

function ActivityRow({ a }: { a: AdminActivity }) {
  return (
    <div
      className="grid grid-cols-[64px_18px_1fr_auto] items-center gap-3 px-4 py-2.5 border-t-[1.5px] border-ink"
      data-testid="admin-activity-row"
    >
      <span className="text-[12px] font-bold text-mute tabular-nums whitespace-nowrap">
        {formatRelativeTime(a.ts)}
      </span>
      <StatusDot status={a.kind === "ok" ? "ok" : a.kind === "fail" ? "fail" : "warn"} />
      <span className="flex flex-col min-w-0">
        <span className="text-[14px] font-extrabold tracking-[-0.01em] truncate">
          {a.text}
          {a.detail && (
            <span className="font-semibold text-mute"> — {a.detail}</span>
          )}
        </span>
      </span>
      <span className="text-[11px] font-bold text-mute">
        attempts: {a.attempts}
      </span>
    </div>
  );
}

function formatRelativeTime(iso: string): string {
  const ts = new Date(iso).getTime();
  if (Number.isNaN(ts)) return iso;
  const diff = ts - Date.now();
  const absMin = Math.abs(diff) / 60_000;
  if (absMin < 1) return diff >= 0 ? "now" : "just now";
  if (absMin < 60) {
    const m = Math.round(absMin);
    return diff >= 0 ? `in ${m}m` : `${m}m ago`;
  }
  if (absMin < 60 * 24) {
    const h = Math.round(absMin / 60);
    return diff >= 0 ? `in ${h}h` : `${h}h ago`;
  }
  const d = Math.round(absMin / (60 * 24));
  return diff >= 0 ? `in ${d}d` : `${d}d ago`;
}
