"use client";

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import clsx from "@/lib/clsx";
import { useActiveGuild, useCurrentUser, useGuilds } from "@/lib/hooks";
import type { Guild } from "@/lib/types";

export function GuildSwitcher({ fullWidth = false }: { fullWidth?: boolean }) {
  const guild = useActiveGuild();
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const onDocClick = (e: MouseEvent) => {
      if (!ref.current?.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", onDocClick);
    return () => document.removeEventListener("mousedown", onDocClick);
  }, []);

  if (!guild) {
    return (
      <div
        className={clsx(
          "h-[46px] rounded-card bg-paper2 border-[1.5px] border-ink/20",
          fullWidth ? "w-full" : "w-[220px]",
        )}
      />
    );
  }

  return (
    <div ref={ref} className={clsx("relative", fullWidth ? "w-full" : "shrink-0")}>
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        className={clsx(
          "inline-flex items-center gap-2.5 h-[46px] rounded-card border-[1.5px] border-ink bg-paper pl-1.5 pr-3",
          "shadow-rest hover:shadow-rest active:shadow-press active:translate-x-[0.5px] active:translate-y-[0.5px]",
          "transition-[box-shadow,transform] select-none",
          fullWidth && "w-full",
        )}
        title={`${guild.name} · #${guild.channel}`}
      >
        <GuildIcon guild={guild} />
        <span className="flex flex-col leading-none min-w-0 flex-1 text-left">
          <span
            className={clsx(
              "text-[15px] font-extrabold tracking-[-0.01em] overflow-hidden text-ellipsis whitespace-nowrap",
              fullWidth ? "max-w-full" : "max-w-[160px]",
            )}
          >
            {guild.name}
          </span>
          <span className="text-[13px] text-mute font-semibold mt-0.5 whitespace-nowrap">
            ● #{guild.channel}
          </span>
        </span>
        <span className="ml-1 text-[18px] text-mute">▾</span>
      </button>

      {open && <GuildDropdown onClose={() => setOpen(false)} fullWidth={fullWidth} />}
    </div>
  );
}

function GuildDropdown({
  onClose,
  fullWidth,
}: {
  onClose: () => void;
  fullWidth?: boolean;
}) {
  const { data: guilds } = useGuilds();
  const { data: user } = useCurrentUser();
  const router = useRouter();

  return (
    <div
      className={clsx(
        "absolute left-0 top-[calc(100%+6px)] z-30 rounded-card border-[1.5px] border-ink bg-paper shadow-rest overflow-hidden",
        fullWidth ? "w-full" : "w-[260px]",
      )}
    >
      <div className="px-3 pt-2.5 pb-1.5 text-[10.5px] font-extrabold tracking-[0.18em] text-mute uppercase border-b-[1px] border-ink/10">
        your servers
      </div>

      {(guilds ?? []).map((g) => (
        <div
          key={g.id}
          className="flex items-center gap-2.5 px-3 py-2.5 border-b-[1px] border-ink/10 last:border-b-0 hover:bg-paper2"
        >
          <GuildIcon guild={g} />
          <span className="flex-1 min-w-0">
            <span className="block text-[15px] font-extrabold tracking-[-0.01em] truncate">
              {g.name}
            </span>
            <span className="block text-[13px] text-mute font-semibold">
              #{g.channel}
            </span>
          </span>
          {user?.admin && (
            <button
              type="button"
              title="Server settings"
              onClick={() => {
                onClose();
                router.push(`/guild/${g.id}/settings`);
              }}
              className="text-mute hover:text-ink text-[16px] p-1 rounded-chip hover:bg-paper2 flex-shrink-0"
            >
              ✏️
            </button>
          )}
        </div>
      ))}

      <div className="px-3 py-2.5">
        <button
          type="button"
          disabled
          className="w-full text-left text-[15px] font-semibold text-mute/50 cursor-not-allowed flex items-center gap-2"
        >
          <span className="inline-flex items-center justify-center w-7 h-7 rounded-chip border-[1.5px] border-ink/20 bg-paper2 text-[14px]">
            +
          </span>
          Add a server
        </button>
      </div>
    </div>
  );
}

function GuildIcon({ guild }: { guild: Guild }) {
  return (
    <span
      className="inline-flex items-center justify-center w-8 h-8 rounded-chip border-[1.5px] border-ink text-[12px] font-extrabold shrink-0"
      style={{ background: guild.color, color: "#0E100D" }}
    >
      {guild.initials}
    </span>
  );
}
