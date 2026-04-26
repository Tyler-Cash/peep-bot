"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import clsx from "@/lib/clsx";
import { Peepo } from "@/components/Peepo";
import { Chunky } from "@/components/ui/Chunky";
import { Avatar } from "@/components/ui/Avatar";
import { useCurrentUser } from "@/lib/hooks";
import { GuildSwitcher } from "./GuildSwitcher";

export function Nav() {
  const { data: user } = useCurrentUser();
  const pathname = usePathname();
  const tabs: Array<{ label: string; href: string }> = [
    { label: "events", href: "/" },
    { label: "rewind", href: "/rewind" },
  ];
  return (
    <nav className="sticky top-0 z-20 bg-paper border-b-[1.5px] border-ink">
      <div className="mx-auto flex max-w-[1200px] items-center gap-4 px-5 py-3">
        <Link href="/" className="flex items-center gap-2.5">
          <span className="inline-flex items-center justify-center w-9 h-9 rounded-[8px] bg-leaf border-[1.5px] border-ink shadow-chunky-sm">
            <Peepo size={22} />
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
                  "rounded-full px-3 py-1.5 text-[13.5px] font-extrabold tracking-[-0.01em] border-[1.5px]",
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
            <Chunky variant="leaf" size="sm">
              + new event
            </Chunky>
          </Link>
          {user && (
            <Avatar
              who={{ name: user.displayName, avatarUrl: user.avatarUrl }}
              size={34}
            />
          )}
        </div>
      </div>
    </nav>
  );
}
