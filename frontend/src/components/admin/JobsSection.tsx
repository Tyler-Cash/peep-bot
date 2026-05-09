"use client";

import { Slab } from "@/components/ui/Slab";
import { Chunky } from "@/components/ui/Chunky";
import { StatusDot } from "./StatusDot";
import { useAdminActivity, useAdminJobs, type AdminJob } from "@/lib/hooks";

export function JobsSection({
  onOpenReplay,
}: {
  onOpenReplay: (job: AdminJob | null) => void;
}) {
  const { data: jobs } = useAdminJobs();
  const { data: activity } = useAdminActivity(null);
  const failures = (activity ?? []).filter((a) => a.kind === "fail").slice(0, 6);

  return (
    <div className="max-w-[1280px] mx-auto px-5 pt-6 pb-16">
      <div className="mb-5">
        <span className="eyebrow">jobs &amp; queue</span>
        <h1 className="mt-1 text-[40px] sm:text-[48px] font-extrabold tracking-[-0.04em] lowercase leading-none">
          the schedule.
        </h1>
        <p className="mt-2 text-[16px] text-ink2 font-medium">
          cron-driven schedulers + reactive listeners.{" "}
          <em>due</em> schedulers emit lifecycle events; listeners react and advance state.
        </p>
      </div>

      <Slab className="p-0 overflow-hidden">
        <div className="grid grid-cols-[18px_2fr_1.4fr_1fr_1fr_1fr_auto] items-center px-4 py-2.5 border-b-[1.5px] border-ink bg-paper2">
          {["", "job", "emits", "cron", "last run", "next run", ""].map((h, i) => (
            <span
              key={i}
              className="eyebrow pr-2.5 text-[11px] tracking-[0.12em]"
            >
              {h}
            </span>
          ))}
        </div>
        {(jobs ?? []).map((j, i) => (
          <div
            key={j.id}
            className="grid grid-cols-[18px_2fr_1.4fr_1fr_1fr_1fr_auto] items-center px-4 py-3 border-t-[1.5px] border-ink/10 first:border-t-0"
            style={i === 0 ? { borderTopWidth: 0 } : undefined}
          >
            <StatusDot status={j.lastStatus} />
            <div className="flex flex-col pr-2.5">
              <span className="text-[14.5px] font-extrabold tracking-[-0.01em]">
                {j.label}
              </span>
              <span className="text-[11.5px] text-mute font-mono font-semibold">
                {j.id}
              </span>
            </div>
            <span
              className={`text-[13px] font-bold pr-2.5 ${j.emits === "—" ? "text-mute" : "text-ink font-mono"}`}
            >
              {j.emits}
            </span>
            <code className="text-[12.5px] font-bold text-ink2 font-mono pr-2.5">
              {j.cron}
            </code>
            <span className="text-[13px] font-bold text-ink2 tabular-nums pr-2.5">
              {j.lastRun ? formatRelative(j.lastRun) : "—"}
            </span>
            <span className="text-[13px] font-bold text-ink2 tabular-nums pr-2.5">
              {j.nextRun ? formatRelative(j.nextRun) : "—"}
            </span>
            <Chunky
              variant="paper"
              size="sm"
              onClick={() => onOpenReplay(j)}
              className="px-3 py-1 text-[12.5px]"
            >
              ↻ replay
            </Chunky>
          </div>
        ))}
        {jobs && jobs.length === 0 && (
          <div className="px-4 py-6 text-mute text-[14px]">no jobs registered.</div>
        )}
      </Slab>

      <div className="mt-6 grid grid-cols-1 lg:grid-cols-2 gap-4">
        <Slab className="p-4">
          <span className="eyebrow">recent failures · all guilds</span>
          <p className="mt-0.5 text-[20px] font-extrabold tracking-[-0.02em]">
            {failures.length} in last hour
          </p>
          <div className="mt-2.5 flex flex-col gap-2">
            {failures.map((a, i) => (
              <div
                key={i}
                className="flex items-start gap-2 px-2.5 py-2 border-[1.5px] border-[#991B1B] rounded-chip bg-[#FFE5E5]"
              >
                <span className="text-[13px] font-extrabold text-[#991B1B]">⚠</span>
                <span className="flex-1 text-[13px] text-ink2 font-semibold">
                  <span className="font-extrabold">{a.text}</span>
                  {a.detail ? ` — ${a.detail}` : ""}
                  <span className="block text-[11.5px] text-mute mt-0.5 font-bold">
                    {formatRelative(a.ts)} · attempts: {a.attempts}
                  </span>
                </span>
              </div>
            ))}
            {failures.length === 0 && (
              <span className="text-[13px] text-mute">nothing red. nice.</span>
            )}
          </div>
        </Slab>
        <Slab className="p-4">
          <span className="eyebrow">how this works</span>
          <p className="mt-2 text-[14px] text-ink2 leading-snug">
            cron jobs are protected by{" "}
            <code className="font-mono font-extrabold">shedlock</code> for distributed
            single-firing. last-run is read from the shedlock table; next-run is computed from the
            cron expression. reactive listeners (rows marked{" "}
            <code className="font-mono font-extrabold">(reactive)</code>) fire when an upstream
            lifecycle event is published — they have no schedule.
          </p>
        </Slab>
      </div>
    </div>
  );
}

function formatRelative(iso: string): string {
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
