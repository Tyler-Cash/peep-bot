"use client";

import clsx from "@/lib/clsx";
import { useActiveGuild } from "@/lib/hooks";
import type { Guild } from "@/lib/types";

export function GuildSwitcher() {
  const guild = useActiveGuild();
  if (!guild) {
    return <div className="h-[46px] w-[220px] rounded-[12px] bg-paper2 border-[1.5px] border-ink/20" />;
  }
  return <GuildPill guild={guild} />;
}

function GuildPill({ guild }: { guild: Guild }) {
  return (
    <div
      className={clsx(
        "inline-flex items-center gap-2.5 rounded-[12px] border-[1.5px] border-ink bg-paper pl-1.5 pr-3 py-1.5 shadow-chunky-sm",
        "select-none shrink-0",
      )}
      title={`${guild.name} · #${guild.channel}`}
    >
      <span
        className="inline-flex items-center justify-center w-8 h-8 rounded-[8px] border-[1.5px] border-ink text-[12px] font-extrabold"
        style={{ background: guild.color, color: "#0E100D" }}
      >
        {guild.initials}
      </span>
      <span className="flex flex-col leading-none">
        <span className="text-[13.5px] font-extrabold tracking-[-0.01em] max-w-[220px] overflow-hidden text-ellipsis whitespace-nowrap">{guild.name}</span>
        <span className="text-[11.5px] text-mute font-semibold mt-0.5 whitespace-nowrap">● #{guild.channel}</span>
      </span>
    </div>
  );
}
