"use client";

import Link from "next/link";
import { useEffect } from "react";
import clsx from "@/lib/clsx";
import { Chunky } from "@/components/ui/Chunky";
import { logout } from "@/lib/hooks";
import { GuildSwitcher } from "./GuildSwitcher";
import { NAV_TABS, isTabActive } from "./navTabs";

export function MobileDrawer({
  pathname,
  onClose,
}: {
  pathname: string;
  onClose: () => void;
}) {
  // Lock background scroll while the drawer is open
  useEffect(() => {
    const prev = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = prev;
    };
  }, []);

  return (
    <div className="md:hidden absolute top-full left-0 right-0 z-40 border-b-[1.5px] border-ink bg-paper shadow-hero">
        <div className="px-4 py-4 flex flex-col gap-3 max-h-[calc(100vh-64px)] overflow-y-auto">
          <div className="flex flex-col gap-1.5">
            {NAV_TABS.map((t) => {
              const active = isTabActive(pathname, t.href);
              return (
                <Link
                  key={t.href}
                  href={t.href}
                  onClick={onClose}
                  className={clsx(
                    "flex items-center h-[52px] rounded-chip px-4 text-[16px] font-extrabold tracking-[-0.01em] border-[1.5px]",
                    active
                      ? "bg-ink text-paper border-ink shadow-rest"
                      : "bg-white text-ink border-ink/15 hover:bg-paper2",
                  )}
                >
                  {t.label}
                </Link>
              );
            })}
          </div>

          <div className="border-t border-dashed border-ink/20 pt-3">
            <span className="block text-[10.5px] font-extrabold tracking-[0.18em] text-mute uppercase mb-2">
              server
            </span>
            <GuildSwitcher fullWidth />
          </div>

          <Link href="/events/new" onClick={onClose} className="mt-1">
            <Chunky
              variant="leaf"
              className="w-full h-[52px] text-[16px] justify-center"
            >
              + new event
            </Chunky>
          </Link>

          <button
            onClick={() => {
              onClose();
              logout();
            }}
            className="mt-2 self-start text-[14px] font-bold text-mute hover:text-ink transition-colors"
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
