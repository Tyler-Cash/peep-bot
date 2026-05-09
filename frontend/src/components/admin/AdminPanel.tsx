"use client";

import { useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { useAdminGuilds, useCurrentUser, type AdminEvent, type AdminGuild } from "@/lib/hooks";
import { AdminSubNav, type AdminSection } from "./AdminSubNav";
import { OverviewSection } from "./OverviewSection";
import { EventsSection } from "./EventsSection";
import { TogglesSection } from "./TogglesSection";
import { JobsSection } from "./JobsSection";
import { GuildsSection } from "./GuildsSection";
import { ReplayModal } from "./ReplayModal";
import type { LifecycleStage } from "./lifecycle";

const ACTIVE_GUILD_KEY = "peepbot.activeGuild";

/**
 * Service-admin only multi-section panel. The user.admin flag is set by the backend's
 * BotAdminService — a static allowlist of Discord snowflakes; this is NOT the per-guild
 * "event-organiser" role. Non-admins are bounced to /. The redirect is client-side only,
 * matching the rest of the app's auth pattern.
 */
export function AdminPanel() {
  const { data: user } = useCurrentUser();
  const { data: guilds } = useAdminGuilds();
  const router = useRouter();

  const [section, setSection] = useState<AdminSection>("overview");
  const [activeGuildId, setActiveGuildIdState] = useState<string | null>(null);
  const [replay, setReplay] = useState<{
    open: boolean;
    event: AdminEvent | null;
    stage: LifecycleStage | null;
  }>({ open: false, event: null, stage: null });

  // Hydrate active guild from localStorage (shared with the public /events tab) → fall back to
  // the first admin guild → otherwise null. Re-runs whenever the admin-guilds list arrives.
  useEffect(() => {
    if (!guilds || guilds.length === 0) return;
    const stored =
      typeof window !== "undefined"
        ? window.localStorage.getItem(ACTIVE_GUILD_KEY)
        : null;
    if (stored && guilds.some((g) => g.guildId === stored)) {
      setActiveGuildIdState(stored);
    } else {
      setActiveGuildIdState(guilds[0].guildId);
    }
  }, [guilds]);

  useEffect(() => {
    if (user && !user.admin) {
      router.replace("/");
    }
  }, [user, router]);

  const activeGuild: AdminGuild | null = useMemo(() => {
    if (!guilds || !activeGuildId) return null;
    return guilds.find((g) => g.guildId === activeGuildId) ?? null;
  }, [guilds, activeGuildId]);

  if (!user?.admin) return null;

  const setActiveGuild = (id: string) => {
    setActiveGuildIdState(id);
    if (typeof window !== "undefined") {
      window.localStorage.setItem(ACTIVE_GUILD_KEY, id);
      window.dispatchEvent(new Event("peepbot:active-guild-changed"));
    }
  };

  const openReplay = (event: AdminEvent | null, stage: LifecycleStage | null) =>
    setReplay({ open: true, event, stage });
  const closeReplay = () => setReplay((r) => ({ ...r, open: false }));

  return (
    <div className="min-h-screen bg-paper">
      <AdminSubNav
        section={section}
        onSelect={setSection}
        onReplay={() => openReplay(null, null)}
      />
      <AdminGuildPicker
        guilds={guilds ?? []}
        activeGuildId={activeGuildId}
        onSelect={setActiveGuild}
      />

      {section === "overview" && (
        <OverviewSection
          activeGuild={activeGuild}
          onJumpEvents={() => setSection("events")}
          onOpenReplay={() => openReplay(null, null)}
        />
      )}
      {section === "events" && (
        <EventsSection activeGuild={activeGuild} onOpenReplay={openReplay} />
      )}
      {section === "toggles" && <TogglesSection activeGuild={activeGuild} />}
      {section === "jobs" && (
        <JobsSection onOpenReplay={() => openReplay(null, null)} />
      )}
      {section === "guilds" && (
        <GuildsSection
          onSelectGuild={(g) => {
            setActiveGuild(g.guildId);
            setSection("overview");
          }}
        />
      )}

      <ReplayModal
        open={replay.open}
        onClose={closeReplay}
        prefillEvent={replay.event}
        prefillStage={replay.stage}
        activeGuild={activeGuild}
      />
    </div>
  );
}

/**
 * Compact picker shown above each admin section. Lives separate from the public-site
 * GuildSwitcher because admins routinely jump between guilds for cross-guild ops, so the dropdown
 * is open by default and always at the page edge — no need to chase it.
 */
function AdminGuildPicker({
  guilds,
  activeGuildId,
  onSelect,
}: {
  guilds: AdminGuild[];
  activeGuildId: string | null;
  onSelect: (id: string) => void;
}) {
  if (guilds.length === 0) return null;
  return (
    <div className="border-b border-ink/10 bg-paper2/40">
      <div className="max-w-[1280px] mx-auto px-5 py-2 flex items-center gap-2 overflow-x-auto">
        <span className="eyebrow text-[10.5px] tracking-[0.16em] text-mute mr-1">
          guild scope
        </span>
        {guilds.map((g) => {
          const active = g.guildId === activeGuildId;
          return (
            <button
              key={g.guildId}
              type="button"
              onClick={() => onSelect(g.guildId)}
              className={
                "h-8 px-3 rounded-chip border-[1.5px] text-[13px] font-extrabold tracking-[-0.01em] whitespace-nowrap " +
                (active
                  ? "border-ink bg-ink text-paper shadow-rest"
                  : "border-transparent bg-transparent text-ink hover:bg-paper2")
              }
            >
              {g.name ?? g.guildId}
            </button>
          );
        })}
      </div>
    </div>
  );
}
