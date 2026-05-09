"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import {
  setActiveGuildId,
  useActiveAdminGuild,
  useCurrentUser,
  type AdminEvent,
} from "@/lib/hooks";
import { AdminSubNav, type AdminSection } from "./AdminSubNav";
import { OverviewSection } from "./OverviewSection";
import { EventsSection } from "./EventsSection";
import { TogglesSection } from "./TogglesSection";
import { JobsSection } from "./JobsSection";
import { GuildsSection } from "./GuildsSection";
import { ReplayModal } from "./ReplayModal";
import type { LifecycleStage } from "./lifecycle";

/**
 * Service-admin only multi-section panel. The user.admin flag is set by the backend's
 * BotAdminService — a static allowlist of Discord snowflakes; this is NOT the per-guild
 * "event-organiser" role. Non-admins are bounced to /. The redirect is client-side only,
 * matching the rest of the app's auth pattern.
 */
export function AdminPanel() {
  const { data: user } = useCurrentUser();
  // Active guild is shared with the rest of the app via the same localStorage key, and
  // resolved against the admin-guilds superset so admins can scope to guilds they aren't
  // a member of. The top-bar GuildSwitcher writes to that key in admin mode.
  const activeGuild = useActiveAdminGuild();
  const router = useRouter();

  const [section, setSection] = useState<AdminSection>("overview");
  const [replay, setReplay] = useState<{
    open: boolean;
    event: AdminEvent | null;
    stage: LifecycleStage | null;
  }>({ open: false, event: null, stage: null });

  useEffect(() => {
    if (user && !user.admin) {
      router.replace("/");
    }
  }, [user, router]);

  if (!user?.admin) return null;

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
            setActiveGuildId(g.guildId);
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
