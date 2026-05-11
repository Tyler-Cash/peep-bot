"use client";

import { useEffect, useRef, useState } from "react";
import { usePathname, useRouter } from "next/navigation";
import clsx from "@/lib/clsx";
import {
  setActiveGuildId,
  useActiveAdminGuild,
  useActiveGuild,
  useAdminGuilds,
  useCurrentUser,
  useGuilds,
  type AdminGuild,
} from "@/lib/hooks";
import type { Guild } from "@/lib/types";
import { isAdminPath, isSettingsPath } from "./navTabs";
import { AddServerModal } from "./AddServerModal";

// Display-shape used by the switcher button and dropdown rows. AdminGuilds and member
// Guilds carry different fields; this collapses both into the small subset the UI needs.
type SwitchableGuild = {
  id: string;
  name: string;
  color: string;
  initials: string;
};

function deriveInitials(name: string): string {
  const trimmed = name.trim();
  if (!trimmed) return "??";
  const words = trimmed.split(/\s+/).slice(0, 2);
  return words.map((w) => w[0]?.toUpperCase() ?? "").join("") || trimmed.slice(0, 2).toUpperCase();
}

// Stable hash → HSL fill so admin-list guilds (no server-supplied color) still get a tinted tile.
function deriveColor(seed: string): string {
  let h = 0;
  for (let i = 0; i < seed.length; i++) h = (h * 31 + seed.charCodeAt(i)) % 360;
  return `hsl(${h}, 60%, 78%)`;
}

function memberToSwitchable(g: Guild): SwitchableGuild {
  return {
    id: g.id ?? "",
    name: g.name ?? "",
    color: g.color ?? deriveColor(g.id ?? g.name ?? ""),
    initials: g.initials ?? deriveInitials(g.name ?? ""),
  };
}

function adminToSwitchable(g: AdminGuild): SwitchableGuild {
  const name = g.name ?? g.guildId;
  return {
    id: g.guildId,
    name,
    color: deriveColor(g.guildId),
    initials: deriveInitials(name),
  };
}

export function GuildSwitcher({ fullWidth = false }: { fullWidth?: boolean }) {
  const pathname = usePathname();
  const path = pathname ?? "";
  const { data: user } = useCurrentUser();
  // Match the Nav's gating: only flip into admin-list mode if the viewer is actually an admin.
  // A non-admin who hits /admin directly will be redirected by AdminGate; until then they see
  // their normal member-guild switcher rather than the empty admin-guilds list.
  const adminMode = isAdminPath(path) && !!user?.admin;
  const settingsMode = isSettingsPath(path);
  const memberGuild = useActiveGuild();
  const adminGuild = useActiveAdminGuild();
  const active = adminMode
    ? adminGuild
      ? adminToSwitchable(adminGuild)
      : null
    : memberGuild
      ? memberToSwitchable(memberGuild)
      : null;
  const [open, setOpen] = useState(false);
  const [addOpen, setAddOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const onDocClick = (e: MouseEvent) => {
      if (!ref.current?.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", onDocClick);
    return () => document.removeEventListener("mousedown", onDocClick);
  }, []);

  if (!active) {
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
          "inline-flex items-center gap-2.5 h-[46px] rounded-card border-[1.5px] border-ink bg-paper text-ink pl-1.5 pr-3",
          "shadow-rest hover:shadow-rest active:shadow-press active:translate-x-[0.5px] active:translate-y-[0.5px]",
          "transition-[box-shadow,transform] select-none",
          fullWidth && "w-full",
        )}
        title={active.name}
      >
        <GuildIcon guild={active} />
        <span className="flex flex-col leading-none min-w-0 flex-1 text-left">
          <span
            className={clsx(
              "text-[15px] font-extrabold tracking-[-0.01em] overflow-hidden text-ellipsis whitespace-nowrap",
              fullWidth ? "max-w-full" : "max-w-[160px]",
            )}
          >
            {active.name}
          </span>

        </span>
        <span className="ml-1 text-[18px] text-mute">▾</span>
      </button>

      {open && (
        <GuildDropdown
          adminMode={adminMode}
          settingsMode={settingsMode}
          activeId={active.id}
          onClose={() => setOpen(false)}
          onOpenAddServer={() => setAddOpen(true)}
          fullWidth={fullWidth}
        />
      )}
      <AddServerModal open={addOpen} onClose={() => setAddOpen(false)} />
    </div>
  );
}

function GuildDropdown({
  adminMode,
  settingsMode,
  activeId,
  onClose,
  onOpenAddServer,
  fullWidth,
}: {
  adminMode: boolean;
  settingsMode: boolean;
  activeId: string;
  onClose: () => void;
  onOpenAddServer: () => void;
  fullWidth?: boolean;
}) {
  const { data: memberGuilds } = useGuilds();
  const { data: adminGuilds } = useAdminGuilds();
  const { data: user } = useCurrentUser();
  const router = useRouter();

  const items: SwitchableGuild[] = adminMode
    ? (adminGuilds ?? []).map(adminToSwitchable)
    : (memberGuilds ?? []).map(memberToSwitchable);

  return (
    <div
      className={clsx(
        "absolute left-0 top-[calc(100%+6px)] z-30 rounded-card border-[1.5px] border-ink bg-paper text-ink shadow-rest overflow-hidden",
        fullWidth ? "w-full" : "w-[260px]",
      )}
    >
      <div className="px-3 pt-2.5 pb-1.5 text-[10.5px] font-extrabold tracking-[0.18em] text-mute uppercase border-b-[1px] border-ink/10">
        {adminMode ? "all servers (admin)" : "your servers"}
      </div>

      {items.map((g) => {
        const isActive = activeId === g.id;
        return (
          <div
            key={g.id}
            className={clsx(
              "relative flex items-center border-b-[1px] border-ink/10 last:border-b-0",
              isActive ? "bg-leafLt/60" : "hover:bg-paper2",
            )}
          >
            {isActive && (
              <span
                className="absolute left-0 top-0 bottom-0 w-[5px] bg-leaf border-r-[1.5px] border-ink"
                aria-hidden
              />
            )}
            <button
              type="button"
              onClick={() => {
                setActiveGuildId(g.id);
                onClose();
                // Stay-on-page semantics differ on settings: switching the active server should
                // also move the user to that server's settings, otherwise the page becomes
                // a contradiction (URL says guild A, switcher says guild B).
                if (settingsMode) {
                  router.push(`/guild/${g.id}/settings`);
                }
              }}
              className="flex-1 min-w-0 flex items-center gap-2.5 px-3 py-2.5 text-left"
              aria-pressed={isActive}
            >
              <GuildIcon guild={g} active={isActive} />
              <span className="flex-1 min-w-0">
                <span className="block text-[15px] font-extrabold tracking-[-0.01em] truncate">
                  {g.name}
                </span>
                {isActive && (
                  <span className="block text-[10px] font-extrabold tracking-[0.18em] text-leafDk uppercase mt-0.5">
                    active
                  </span>
                )}
              </span>
            </button>
            {!adminMode && user?.ownedGuildIds?.includes(g.id) && (
              <button
                type="button"
                title="Server settings"
                aria-label={`Settings for ${g.name}`}
                onClick={() => {
                  setActiveGuildId(g.id);
                  onClose();
                  router.push(`/guild/${g.id}/settings`);
                }}
                className="mr-2 inline-flex items-center justify-center w-9 h-9 rounded-chip border-[1.5px] border-ink bg-paper2 text-[16px] text-ink shadow-rest hover:bg-paper active:shadow-press active:translate-x-[0.5px] active:translate-y-[0.5px] transition-[box-shadow,transform] flex-shrink-0"
              >
                ⚙
              </button>
            )}
          </div>
        );
      })}

      {!adminMode && (
        <div className="border-t-[1px] border-ink/10 hover:bg-paper2">
          <button
            type="button"
            onClick={() => {
              onClose();
              onOpenAddServer();
            }}
            className="w-full flex items-center gap-2.5 px-3 py-2.5 text-left text-[15px] font-extrabold tracking-[-0.01em] text-ink"
          >
            <span className="inline-flex items-center justify-center w-8 h-8 rounded-chip border-[1.5px] border-ink bg-paper2 text-[16px] shrink-0">
              +
            </span>
            Add a server
          </button>
        </div>
      )}
    </div>
  );
}

function GuildIcon({ guild, active = false }: { guild: SwitchableGuild; active?: boolean }) {
  return (
    <span
      className={clsx(
        "inline-flex items-center justify-center w-8 h-8 rounded-chip border-[1.5px] border-ink text-[12px] font-extrabold shrink-0",
        active && "shadow-rest",
      )}
      style={{ background: guild.color, color: "#0E100D" }}
    >
      {guild.initials}
    </span>
  );
}
