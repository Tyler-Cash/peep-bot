"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useState } from "react";
import clsx from "@/lib/clsx";
import { Peepo } from "@/components/Peepo";
import { Chunky } from "@/components/ui/Chunky";
import { Avatar } from "@/components/ui/Avatar";
import { useCurrentUser, logout } from "@/lib/hooks";
import { GuildSwitcher } from "./GuildSwitcher";

const TABS: Array<{ label: string; href: string }> = [
  { label: "events", href: "/" },
  { label: "gallery", href: "/gallery" },
  { label: "rewind", href: "/rewind" },
];

function isActive(pathname: string, href: string) {
  return href === "/"
    ? pathname === "/"
    : pathname === href || pathname.startsWith(href + "/");
}

export function Nav() {
  const { data: user } = useCurrentUser();
  const pathname = usePathname();
  const [menuOpen, setMenuOpen] = useState(false);

  // Close the mobile menu whenever the route changes
  useEffect(() => {
    setMenuOpen(false);
  }, [pathname]);

  // Lock background scroll while the drawer is open
  useEffect(() => {
    if (!menuOpen) return;
    const prev = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = prev;
    };
  }, [menuOpen]);

  return (
    <nav className="sticky top-0 z-20 bg-white border-b-[1.5px] border-ink">
      <div className="mx-auto flex max-w-[1200px] items-center gap-3 sm:gap-4 px-4 sm:px-5 py-3">
        <Link href="/" className="flex items-center gap-2.5 min-w-0">
          <span className="inline-flex items-center justify-center w-[42px] h-[42px] sm:w-[46px] sm:h-[46px] rounded-card bg-leaf border-[1.5px] border-ink shadow-rest shrink-0">
            <Peepo size={26} />
          </span>
          <span className="flex flex-col leading-none min-w-0">
            <span className="text-[16px] sm:text-[17px] font-extrabold tracking-[-0.02em] truncate">
              peepbot
            </span>
            <span className="hidden sm:inline text-[10.5px] font-extrabold tracking-[0.18em] text-mute mt-0.5">
              PLANS, SORTED
            </span>
          </span>
        </Link>

        {/* Desktop tabs */}
        <div className="hidden md:flex ml-4 items-center gap-2">
          {TABS.map((t) => {
            const active = isActive(pathname, t.href);
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

        <div className="flex-1" />

        {/* Desktop right cluster */}
        <div className="hidden md:flex items-center gap-2.5">
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

        {/* Mobile right cluster */}
        <div className="flex md:hidden items-center gap-2">
          {user && (
            <Avatar
              who={{ name: user.displayName, avatarUrl: user.avatarUrl }}
              size={42}
            />
          )}
          <button
            type="button"
            onClick={() => setMenuOpen((o) => !o)}
            aria-label={menuOpen ? "close menu" : "open menu"}
            aria-expanded={menuOpen}
            className="inline-flex items-center justify-center w-[44px] h-[44px] rounded-chip border-[1.5px] border-ink bg-paper shadow-rest active:shadow-press active:translate-x-[1px] active:translate-y-[1px] transition-[box-shadow,transform]"
          >
            <HamburgerIcon open={menuOpen} />
          </button>
        </div>
      </div>

      {menuOpen && (
        <MobileDrawer pathname={pathname} onClose={() => setMenuOpen(false)} />
      )}
    </nav>
  );
}

function MobileDrawer({
  pathname,
  onClose,
}: {
  pathname: string;
  onClose: () => void;
}) {
  return (
    <>
      <div
        className="md:hidden fixed inset-0 top-[64px] z-30 bg-ink/40 backdrop-blur-sm"
        onClick={onClose}
        aria-hidden
      />
      <div className="md:hidden absolute top-full left-0 right-0 z-40 border-b-[1.5px] border-ink bg-paper shadow-hero">
        <div className="px-4 py-4 flex flex-col gap-3 max-h-[calc(100vh-64px)] overflow-y-auto">
          <div className="flex flex-col gap-1.5">
            {TABS.map((t) => {
              const active = isActive(pathname, t.href);
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
            <Chunky variant="leaf" className="w-full h-[52px] text-[16px] justify-center">
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
    </>
  );
}

function HamburgerIcon({ open }: { open: boolean }) {
  return (
    <svg
      width="22"
      height="22"
      viewBox="0 0 22 22"
      fill="none"
      stroke="#0E100D"
      strokeWidth="2.2"
      strokeLinecap="round"
      aria-hidden
    >
      {open ? (
        <>
          <line x1="4" y1="4" x2="18" y2="18" />
          <line x1="18" y1="4" x2="4" y2="18" />
        </>
      ) : (
        <>
          <line x1="3" y1="6" x2="19" y2="6" />
          <line x1="3" y1="11" x2="19" y2="11" />
          <line x1="3" y1="16" x2="19" y2="16" />
        </>
      )}
    </svg>
  );
}
