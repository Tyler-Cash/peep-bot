"use client";

import clsx from "@/lib/clsx";
import { Chunky } from "@/components/ui/Chunky";

export type AdminSection =
  | "overview"
  | "events"
  | "toggles"
  | "jobs"
  | "guilds";

const TABS: Array<{ id: AdminSection; label: string }> = [
  { id: "overview", label: "overview" },
  { id: "events", label: "events" },
  { id: "toggles", label: "toggles" },
  { id: "jobs", label: "jobs" },
  { id: "guilds", label: "guilds" },
];

export function AdminSubNav({
  section,
  onSelect,
  onReplay,
}: {
  section: AdminSection;
  onSelect: (section: AdminSection) => void;
  onReplay: () => void;
}) {
  return (
    <div className="border-b-[1.5px] border-ink/10 bg-paper">
      <div className="max-w-[1280px] mx-auto px-5 py-2.5 flex items-center gap-2 flex-wrap">
        {TABS.map((tab) => {
          const active = section === tab.id;
          return (
            <button
              key={tab.id}
              type="button"
              onClick={() => onSelect(tab.id)}
              className={clsx(
                "h-9 px-4 rounded-chip text-[14px] font-extrabold tracking-[-0.01em] border-[1.5px]",
                "transition-[box-shadow,transform]",
                active
                  ? "bg-paper border-ink shadow-rest text-ink"
                  : "bg-transparent border-transparent text-ink hover:bg-paper2",
              )}
              aria-current={active ? "page" : undefined}
            >
              {tab.label}
            </button>
          );
        })}
        <span className="flex-1" />
        <Chunky
          variant="paper"
          size="sm"
          onClick={onReplay}
          className="h-9 px-3 text-[13px]"
        >
          ↻ replay…
        </Chunky>
      </div>
    </div>
  );
}
