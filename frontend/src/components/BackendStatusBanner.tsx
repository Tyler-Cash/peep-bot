"use client";

import { useEffect, useState } from "react";
import { apiFetch, BackendUnreachable } from "@/lib/api";

export function BackendStatusBanner() {
  const [down, setDown] = useState(false);

  useEffect(() => {
    let cancelled = false;
    const ping = async () => {
      try {
        await apiFetch("/auth/is-logged-in");
        if (!cancelled) setDown(false);
      } catch (e) {
        if (!cancelled) setDown(e instanceof BackendUnreachable);
      }
    };
    ping();
    const id = window.setInterval(ping, 30_000);
    return () => {
      cancelled = true;
      window.clearInterval(id);
    };
  }, []);

  if (!down) return null;
  return (
    <div className="bg-ink text-paper text-[13px] font-semibold text-center py-1.5 border-b-[1.5px] border-ink">
      🐸 homelab is napping — showing last-known events.
    </div>
  );
}
