"use client";

import Link from "next/link";
import clsx from "@/lib/clsx";
import { Avatar } from "@/components/ui/Avatar";
import { Chunky } from "@/components/ui/Chunky";
import { logout, useActiveGuild, useCurrentUser, useGuildFeatures, useGuilds } from "@/lib/hooks";
import { GuildSwitcher } from "./GuildSwitcher";
import { NAV_TABS, TAB_TILTS, filterNavTabs, isTabActive } from "./navTabs";

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
      <div className="hidden md:flex ml-1.5 items-center gap-2.5">
        {showChrome && visibleTabs.map((t) => {
          const active = isTabActive(pathname, t.href);
          const tilt = TAB_TILTS[t.href] ?? 0;
          // Press shadow palette swaps with the bar background so the chunky
          // shadow stays visible against ink in admin mode.
          const pressShadow = adminMode
            ? "active:shadow-[1px_1px_0_#F5F1E8]"
            : "active:shadow-[1px_1px_0_#0E100D]";
          const restShadow = adminMode
            ? "shadow-[2px_2px_0_#F5F1E8]"
            : "shadow-[2px_2px_0_#0E100D]";
          return (
            <Link
              key={t.href}
              href={t.href}
              style={{ transform: `rotate(${tilt}deg)` }}
              className={clsx(
                "inline-flex items-center justify-center h-[42px] rounded-[11px] px-[18px] text-[14px] font-extrabold tracking-[-0.01em] border-[1.5px] lowercase gap-2",
                "transition-[box-shadow]",
                pressShadow,
                adminMode
                  ? active
                    ? "bg-paper text-ink border-paper shadow-rest"
                    : clsx("bg-ink text-paper border-paper hover:bg-ink2", restShadow)
                  : active
                    ? "bg-leaf text-ink border-ink shadow-rest"
                    : clsx("bg-white text-ink border-ink hover:bg-paper", restShadow),
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
                <Chunky variant="leaf" className="h-[42px] px-4 text-[14px]">
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
              size={42}
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
