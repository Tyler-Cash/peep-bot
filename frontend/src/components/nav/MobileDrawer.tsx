"use client";

import Link from "next/link";
import { useEffect } from "react";
import clsx from "@/lib/clsx";
import { Chunky } from "@/components/ui/Chunky";
import { logout, useActiveGuild, useCurrentUser, useGuildFeatures, useGuilds } from "@/lib/hooks";
import { GuildSwitcher } from "./GuildSwitcher";
import { NAV_TABS, filterNavTabs, isTabActive } from "./navTabs";

export function MobileDrawer({
  pathname,
  adminMode,
  onClose,
}: {
  pathname: string;
  adminMode: boolean;
  onClose: () => void;
}) {
  const { data: user } = useCurrentUser();
  const activeGuild = useActiveGuild();
  const { data: features } = useGuildFeatures(activeGuild?.id);
  const { data: guilds } = useGuilds();
  const hasGuilds = !guilds || guilds.length > 0;
  const showChrome = hasGuilds || !!user?.admin;
  const visibleTabs = filterNavTabs(NAV_TABS, features, user);

  // Lock background scroll while the drawer is open
  useEffect(() => {
    const prev = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = prev;
    };
  }, []);

  return (
    <div
      className={clsx(
        "md:hidden absolute top-full left-0 right-0 z-40 border-b-[1.5px] shadow-hero",
        adminMode ? "border-paper bg-ink text-paper" : "border-ink bg-paper text-ink",
      )}
    >
        <div className="px-4 py-4 flex flex-col gap-3 max-h-[calc(100vh-64px)] overflow-y-auto">
          {showChrome && (
            <div className="flex flex-col gap-1.5">
              {visibleTabs.map((t) => {
                const active = isTabActive(pathname, t.href);
                return (
                  <Link
                    key={t.href}
                    href={t.href}
                    onClick={onClose}
                    className={clsx(
                      "flex items-center h-[52px] rounded-chip px-4 text-[16px] font-extrabold tracking-[-0.01em] border-[1.5px]",
                      active
                        ? adminMode
                          ? "bg-paper text-ink border-paper shadow-rest"
                          : "bg-ink text-paper border-ink shadow-rest"
                        : adminMode
                          ? "bg-transparent text-paper border-paper/30 hover:bg-paper/10"
                          : "bg-white text-ink border-ink/15 hover:bg-paper2",
                    )}
                  >
                    {t.label}
                  </Link>
                );
              })}
            </div>
          )}

          {showChrome && (
            <div
              className={clsx(
                "border-t border-dashed pt-3",
                adminMode ? "border-paper/30" : "border-ink/20",
              )}
            >
              <span
                className={clsx(
                  "block text-[10.5px] font-extrabold tracking-[0.18em] uppercase mb-2",
                  adminMode ? "text-paper/70" : "text-mute",
                )}
              >
                server
              </span>
              <GuildSwitcher fullWidth />
            </div>
          )}

          {!adminMode && hasGuilds && (
            <Link href="/events/new" onClick={onClose} className="mt-1">
              <Chunky
                variant="leaf"
                className="w-full h-[52px] text-[16px] justify-center"
              >
                + new event
              </Chunky>
            </Link>
          )}

          <button
            onClick={() => {
              onClose();
              logout();
            }}
            className={clsx(
              "mt-2 self-start text-[14px] font-bold transition-colors",
              adminMode ? "text-paper/70 hover:text-paper" : "text-mute hover:text-ink",
            )}
          >
            log out
          </button>
        </div>
      </div>
  );
}

// Backdrop scrim is rendered separately by <Nav> outside the <nav> element so
// its blur sits *behind* the navbar (which paints over it with bg-white) —
// otherwise the navbar's bottom edge gets visibly blurred along with the page.
export function MobileDrawerScrim({ onClose }: { onClose: () => void }) {
  return (
    <div
      className="md:hidden fixed inset-0 z-10 bg-ink/40 backdrop-blur-sm"
      onClick={onClose}
      aria-hidden
    />
  );
}
