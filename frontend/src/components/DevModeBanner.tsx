"use client";

import { useEffect, useState } from "react";
import { isDevModeActive, deactivateDevMode } from "@/lib/devMode";

export function DevModeBanner() {
  const [active, setActive] = useState(false);

  useEffect(() => {
    setActive(isDevModeActive());
  }, []);

  if (!active) return null;

  const onDeactivate = () => {
    deactivateDevMode();
    window.location.reload();
  };

  return (
    <div className="flex items-center justify-between gap-4 border-b border-ink/20 bg-leaf/70 px-5 py-2 text-[13px] font-bold">
      <span>⚙ dev mode — logged in as Otis (mock)</span>
      <button
        onClick={onDeactivate}
        className="font-bold underline underline-offset-2 decoration-2 text-ink/60 hover:text-ink transition-colors"
      >
        deactivate
      </button>
    </div>
  );
}
