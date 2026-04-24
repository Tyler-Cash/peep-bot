import type { ReactNode } from "react";
import { Nav } from "./nav/Nav";
import { BackendStatusBanner } from "./BackendStatusBanner";

export function AppShell({ children }: { children: ReactNode }) {
  return (
    <div className="min-h-screen bg-paper text-ink">
      <BackendStatusBanner />
      <Nav />
      {children}
    </div>
  );
}
