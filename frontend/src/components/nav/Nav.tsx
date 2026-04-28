"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useState } from "react";
import { Peepo } from "@/components/Peepo";
import { Avatar } from "@/components/ui/Avatar";
import { useCurrentUser } from "@/lib/hooks";
import { DesktopBar } from "./DesktopBar";
import { HamburgerIcon } from "./HamburgerIcon";
import { MobileDrawer, MobileDrawerScrim } from "./MobileDrawer";

export function Nav() {
  const { data: user } = useCurrentUser();
  const pathname = usePathname();
  const [menuOpen, setMenuOpen] = useState(false);

  // Auto-close the drawer whenever the route changes
  useEffect(() => {
    setMenuOpen(false);
  }, [pathname]);

  return (
    <>
    {menuOpen && <MobileDrawerScrim onClose={() => setMenuOpen(false)} />}
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

        <DesktopBar pathname={pathname} />

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
    </>
  );
}
