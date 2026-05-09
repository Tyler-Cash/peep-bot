"use client";

import { Slab } from "@/components/ui/Slab";
import { Chunky } from "@/components/ui/Chunky";
import { StatusDot } from "./StatusDot";
import { useAdminGuilds, type AdminGuild } from "@/lib/hooks";

const FEATURE_KEYS = [
  "immichEnabled",
  "googleAutocompleteEnabled",
  "rewindEnabled",
  "contractsEnabled",
] as const;

export function GuildsSection({
  onSelectGuild,
}: {
  onSelectGuild: (g: AdminGuild) => void;
}) {
  const { data: guilds } = useAdminGuilds();
  const rows = guilds ?? [];
  const needsAttention = rows.filter(
    (g) => g.active && ((g.failingInvocations ?? 0) > 0),
  ).length;

  return (
    <div className="max-w-[1280px] mx-auto px-5 pt-6 pb-16">
      <div className="mb-5 flex items-end justify-between gap-4 flex-wrap">
        <div>
          <span className="eyebrow">all guilds</span>
          <h1 className="mt-1 text-[40px] sm:text-[48px] font-extrabold tracking-[-0.04em] lowercase leading-none">
            every server, at a glance.
          </h1>
          <p className="mt-2 text-[16px] text-ink2 font-medium">
            {rows.length} guilds · {needsAttention} need attention.
          </p>
        </div>
        <span className="inline-flex items-center gap-2 rounded-chip border-[1.5px] border-ink bg-white px-3 py-1.5 shadow-rest text-[13px] font-bold">
          <span className="w-2 h-2 rounded-full bg-[#5FA838]" />
          super-admin only
        </span>
      </div>

      <Slab className="p-0 overflow-hidden">
        <div className="grid grid-cols-[18px_2fr_1fr_1.4fr_1.4fr_1fr_1fr_auto] items-center px-4 py-2.5 bg-paper2 border-b-[1.5px] border-ink">
          {[
            "",
            "guild",
            "members",
            "upcoming / total",
            "features on",
            "fails 24h",
            "channel",
            "",
          ].map((h, i) => (
            <span
              key={i}
              className="eyebrow pr-2.5 text-[11px] tracking-[0.12em]"
            >
              {h}
            </span>
          ))}
        </div>
        {rows.map((g, i) => {
          const onCount = FEATURE_KEYS.filter((k) => g[k]).length;
          const failing = g.failingInvocations ?? 0;
          const status = !g.active ? "muted" : failing > 0 ? "warn" : "ok";
          return (
            <div
              key={g.guildId}
              className="grid grid-cols-[18px_2fr_1fr_1.4fr_1.4fr_1fr_1fr_auto] items-center px-4 py-3.5 border-t-[1.5px] border-ink/10 first:border-t-0"
              style={[
                i === 0 ? { borderTopWidth: 0 } : {},
                !g.active ? { opacity: 0.5 } : {},
              ].reduce((a, b) => ({ ...a, ...b }), {})}
            >
              <StatusDot status={status} />
              <div className="flex items-center gap-2.5 pr-2.5 min-w-0">
                <span className="w-9 h-9 rounded-chip border-[1.5px] border-ink bg-paper2 inline-flex items-center justify-center font-extrabold text-[13px] flex-shrink-0">
                  {(g.name ?? g.guildId).slice(0, 2).toUpperCase()}
                </span>
                <div className="flex flex-col leading-snug min-w-0">
                  <span className="text-[15px] font-extrabold tracking-[-0.01em] truncate">
                    {g.name ?? g.guildId}
                  </span>
                  {g.locationName && (
                    <span className="text-[12px] text-mute font-bold truncate">
                      📍 {g.locationName}
                    </span>
                  )}
                </div>
              </div>
              <span className="text-[14px] font-extrabold tabular-nums">
                {g.memberCount ?? "—"}
              </span>
              <span className="text-[14px] font-extrabold tabular-nums">
                <span className={(g.upcomingEventCount ?? 0) > 0 ? "text-ink" : "text-mute"}>
                  {g.upcomingEventCount ?? 0}
                </span>
                <span className="text-mute font-bold"> / {g.totalEventCount ?? 0}</span>
              </span>
              <span className="flex items-center gap-2 text-[13px] font-bold text-ink2">
                <span className="inline-block w-20 h-2 border-[1.5px] border-ink rounded-md bg-paper2 overflow-hidden relative">
                  <span
                    className="absolute left-0 top-0 bottom-0 bg-leaf"
                    style={{ width: `${(onCount / FEATURE_KEYS.length) * 100}%` }}
                  />
                </span>
                {onCount}/{FEATURE_KEYS.length}
              </span>
              <span
                className={`text-[14px] font-extrabold tabular-nums ${failing > 0 ? "text-[#991B1B]" : "text-mute"}`}
              >
                {failing}
              </span>
              <span className="text-[12.5px] font-bold text-mute truncate pr-2.5">
                {g.channelName ? `● ${g.channelName}` : "—"}
              </span>
              <Chunky
                variant="paper"
                size="sm"
                onClick={() => onSelectGuild(g)}
                className="px-3 py-1 text-[12.5px]"
              >
                open →
              </Chunky>
            </div>
          );
        })}
        {!guilds && (
          <div className="px-4 py-6 text-mute text-[14px]">loading guilds…</div>
        )}
        {guilds && guilds.length === 0 && (
          <div className="px-4 py-6 text-mute text-[14px]">
            no guilds yet — peepbot isn&apos;t in any servers.
          </div>
        )}
      </Slab>
    </div>
  );
}
