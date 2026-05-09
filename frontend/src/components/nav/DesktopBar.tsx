"use client";

import Link from "next/link";
import clsx from "@/lib/clsx";
import { Avatar } from "@/components/ui/Avatar";
import { Chunky } from "@/components/ui/Chunky";
import { logout, useActiveGuild, useCurrentUser, useGuildFeatures, useGuilds } from "@/lib/hooks";
import { GuildSwitcher } from "./GuildSwitcher";
import { NAV_TABS, filterNavTabs, isTabActive } from "./navTabs";

export function DesktopBar({ pathname, adminMode }: { pathname: string; adminMode: boolean }) {
  const { data: user } = useCurrentUser();
  const activeGuild = useActiveGuild();
  const { data: features } = useGuildFeatures(activeGuild?.id);
  const { data: guilds } = useGuilds();
  // While `guilds` is undefined (loading) keep the chrome visible; only hide
  // it once we know the user is in zero servers. Admins still get the full bar
  // (admin tab + admin-mode switcher) even when they're in zero member guilds.
  const hasGuilds = !guilds || guilds.length > 0;
  const showChrome = hasGuilds || !!user?.admin;
  const visibleTabs = filterNavTabs(NAV_TABS, features, user);

  return (
    <>
      <div className="hidden md:flex ml-4 items-center gap-2">
        {showChrome && visibleTabs.map((t) => {
          const active = isTabActive(pathname, t.href);
          return (
            <Link
              key={t.href}
              href={t.href}
              className={clsx(
                "flex items-center justify-center h-[46px] rounded-chip px-5 text-[14.5px] font-extrabold tracking-[-0.01em] border-[1.5px]",
                active
                  ? adminMode
                    ? "bg-paper text-ink border-paper shadow-rest"
                    : "bg-ink text-paper border-ink shadow-rest"
                  : adminMode
                    ? "bg-transparent text-paper border-transparent hover:bg-paper/10"
                    : "bg-transparent text-ink border-transparent hover:bg-paper2",
              )}
            >
              {t.label}
            </Link>
          );
        })}
      </div>

      <div className="flex-1 hidden md:block" />

      <div className="hidden md:flex items-center gap-2.5">
        {showChrome && (
          <>
            <GuildSwitcher />
            {!adminMode && hasGuilds && (
              <Link href="/events/new">
                <Chunky variant="leaf" className="h-[46px] px-5 text-[14.5px]">
                  + new event
                </Chunky>
              </Link>
            )}
          </>
        )}
        {user && (
          <>
            <Avatar
              who={{ name: user.displayName, avatarUrl: user.avatarUrl }}
              size={46}
            />
            <button
              onClick={() => logout()}
              className={clsx(
                "text-[12px] font-bold transition-colors",
                adminMode ? "text-paper/70 hover:text-paper" : "text-mute hover:text-ink",
              )}
              title="log out"
            >
              log out
            </button>
          </>
        )}
      </div>
    </>
  );
}
