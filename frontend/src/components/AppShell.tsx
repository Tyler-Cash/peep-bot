import type { ReactNode } from "react";
import { Nav } from "./nav/Nav";
import { BackendStatusBanner } from "./BackendStatusBanner";
import { DevModeBanner } from "./DevModeBanner";

export function AppShell({ children }: { children: ReactNode }) {
  return (
    <div className="min-h-screen bg-paper text-ink">
      <DevModeBanner />
      <BackendStatusBanner />
      <Nav />
      {children}
    </div>
  );
}
