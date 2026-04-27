"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import clsx from "@/lib/clsx";
import { Peepo } from "@/components/Peepo";
import { Chunky } from "@/components/ui/Chunky";
import { Avatar } from "@/components/ui/Avatar";
import { useCurrentUser, logout } from "@/lib/hooks";
import { GuildSwitcher } from "./GuildSwitcher";

export function Nav() {
  const { data: user } = useCurrentUser();
  const pathname = usePathname();
  const tabs: Array<{ label: string; href: string }> = [
    { label: "events", href: "/" },
    { label: "rewind", href: "/rewind" },
  ];
  return (
    <nav className="sticky top-0 z-20 bg-white border-b-[1.5px] border-ink">
      <div className="mx-auto flex max-w-[1200px] items-center gap-4 px-5 py-3">
        <Link href="/" className="flex items-center gap-2.5">
          <span className="inline-flex items-center justify-center w-[46px] h-[46px] rounded-[12px] bg-leaf border-[1.5px] border-ink shadow-chunky-sm shrink-0">
            <Peepo size={28} />
          </span>
          <span className="flex flex-col leading-none">
            <span className="text-[17px] font-extrabold tracking-[-0.02em]">peepbot</span>
            <span className="text-[10.5px] font-extrabold tracking-[0.18em] text-mute mt-0.5">
              PLANS, SORTED
            </span>
          </span>
        </Link>

        <div className="ml-4 flex items-center gap-2">
          {tabs.map((t) => {
            const active = pathname === t.href || (t.href === "/" && pathname === "/");
            return (
              <Link
                key={t.href}
                href={t.href}
                className={clsx(
                  "flex items-center justify-center h-[46px] rounded-full px-5 text-[14.5px] font-extrabold tracking-[-0.01em] border-[1.5px]",
                  active
                    ? "bg-ink text-paper border-ink shadow-chunky-leaf"
                    : "bg-transparent text-ink border-transparent hover:bg-paper2",
                )}
              >
                {t.label}
              </Link>
            );
          })}
        </div>

        <div className="flex-1" />

        <div className="flex items-center gap-2.5">
          <GuildSwitcher />
          <Link href="/events/new">
            <Chunky variant="leaf" className="h-[46px] px-5 text-[14.5px]">
              + new event
            </Chunky>
          </Link>
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
      </div>
    </nav>
  );
}
