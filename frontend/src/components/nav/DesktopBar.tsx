"use client";

import Link from "next/link";
import clsx from "@/lib/clsx";
import { Avatar } from "@/components/ui/Avatar";
import { Chunky } from "@/components/ui/Chunky";
import { logout, useActiveGuild, useCurrentUser, useGuildFeatures, useGuilds } from "@/lib/hooks";
import { GuildSwitcher } from "./GuildSwitcher";
import { NAV_TABS, filterNavTabs, isTabActive } from "./navTabs";

export function DesktopBar({ pathname }: { pathname: string }) {
  const { data: user } = useCurrentUser();
  const activeGuild = useActiveGuild();
  const { data: features } = useGuildFeatures(activeGuild?.id);
  const { data: guilds } = useGuilds();
  // While `guilds` is undefined (loading) keep the chrome visible; only hide
  // it once we know the user is in zero servers.
  const hasGuilds = !guilds || guilds.length > 0;
  const visibleTabs = filterNavTabs(NAV_TABS, features);

  return (
    <>
      <div className="hidden md:flex ml-4 items-center gap-2">
        {hasGuilds && visibleTabs.map((t) => {
          const active = isTabActive(pathname, t.href);
          return (
            <Link
              key={t.href}
              href={t.href}
              className={clsx(
                "flex items-center justify-center h-[46px] rounded-chip px-5 text-[14.5px] font-extrabold tracking-[-0.01em] border-[1.5px]",
                active
                  ? "bg-ink text-paper border-ink shadow-rest"
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
        {hasGuilds && (
          <>
            <GuildSwitcher />
            <Link href="/events/new">
              <Chunky variant="leaf" className="h-[46px] px-5 text-[14.5px]">
                + new event
              </Chunky>
            </Link>
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
              className="text-[12px] font-bold text-mute hover:text-ink transition-colors"
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
