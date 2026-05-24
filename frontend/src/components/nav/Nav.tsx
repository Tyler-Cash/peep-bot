"use client";

import Image from "next/image";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useState } from "react";
import clsx from "@/lib/clsx";
import { Avatar } from "@/components/ui/Avatar";
import { useCurrentUser } from "@/lib/hooks";
import { DesktopBar } from "./DesktopBar";
import { HamburgerIcon } from "./HamburgerIcon";
import { MobileDrawer, MobileDrawerScrim } from "./MobileDrawer";
import { isAdminPath } from "./navTabs";

export function Nav() {
  const { data: user } = useCurrentUser();
  const pathname = usePathname();
  // Gate admin-mode chrome on the admin flag too. Without this, a non-admin who hits
  // /admin directly would see the inverted bar for one frame before AdminGate's
  // useEffect-redirect fires.
  const adminMode = isAdminPath(pathname ?? "") && !!user?.admin;
  const [menuOpen, setMenuOpen] = useState(false);

  // Auto-close the drawer whenever the route changes. Tracking the path in state
  // and resetting during render (instead of in an effect) avoids the extra
  // render pass that `react-hooks/set-state-in-effect` warns about.
  const [menuPath, setMenuPath] = useState(pathname);
  if (pathname !== menuPath) {
    setMenuPath(pathname);
    setMenuOpen(false);
  }

  return (
    <>
    {menuOpen && <MobileDrawerScrim onClose={() => setMenuOpen(false)} />}
    <nav
      className={clsx(
        "sticky top-0 z-20 border-b-[1.5px]",
        // Admin mode: invert the bar to make the elevated context unmistakable.
        adminMode
          ? "bg-ink text-paper border-paper"
          : "bg-white text-ink border-ink",
      )}
      data-admin-mode={adminMode || undefined}
    >
      <div className="mx-auto flex max-w-[1200px] items-center gap-3 sm:gap-4 px-4 sm:px-5 py-3">
        <Link
          href={adminMode ? "/admin" : "/"}
          className={clsx(
            "inline-flex items-center gap-2.5 min-w-0 rounded-[12px] border-[1.5px] py-[5px] pl-[5px] pr-3.5 sm:pr-4 transition-[box-shadow]",
            adminMode
              ? "bg-paper border-paper text-ink shadow-[2px_2px_0_#7BC24F] active:shadow-[1px_1px_0_#7BC24F]"
              : "bg-ink border-ink text-paper shadow-[2px_2px_0_#4E8A2C] active:shadow-[1px_1px_0_#4E8A2C]",
          )}
        >
          <span className="inline-flex items-center justify-center w-[38px] h-[38px] shrink-0 overflow-visible">
            <Image
              src="/peepos/peepo.png"
              alt=""
              aria-hidden
              width={36}
              height={36}
              className="w-[36px] h-[36px] object-contain"
              priority
            />
          </span>
          <span className="flex flex-col leading-none min-w-0">
            <span className="text-[16px] sm:text-[17px] font-extrabold tracking-[-0.02em] truncate">
              peepbot
            </span>
            <span
              className={clsx(
                "hidden sm:inline text-[10.5px] font-extrabold tracking-[0.18em] mt-[3px]",
                adminMode ? "text-mute" : "text-muteDk",
              )}
            >
              {adminMode ? "ADMIN MODE" : "PLANS, SORTED"}
            </span>
          </span>
        </Link>

        <DesktopBar pathname={pathname} adminMode={adminMode} />

        <div className="md:hidden flex-1" />

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
            className={clsx(
              "inline-flex items-center justify-center w-[44px] h-[44px] rounded-chip border-[1.5px] shadow-rest active:shadow-press active:translate-x-[1px] active:translate-y-[1px] transition-[box-shadow,transform]",
              adminMode ? "border-paper bg-ink text-paper" : "border-ink bg-paper",
            )}
          >
            <HamburgerIcon open={menuOpen} />
          </button>
        </div>
      </div>

      {menuOpen && (
        <MobileDrawer pathname={pathname} adminMode={adminMode} onClose={() => setMenuOpen(false)} />
      )}
    </nav>
    </>
  );
}
