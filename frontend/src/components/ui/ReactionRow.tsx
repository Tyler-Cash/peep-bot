"use client";

import clsx from "@/lib/clsx";
import type { RsvpStatus } from "@/lib/types";

type Counts = { going: number; maybe: number; declined: number };

export function ReactionRow({
  counts,
  active,
  onPick,
  compact,
}: {
  counts: Counts;
  active?: RsvpStatus | null;
  onPick?: (status: RsvpStatus) => void;
  compact?: boolean;
}) {
  const items: Array<{ status: RsvpStatus; emoji: string; n: number }> = [
    { status: "going", emoji: "✅", n: counts.going },
    { status: "maybe", emoji: "🤔", n: counts.maybe },
    { status: "declined", emoji: "❌", n: counts.declined },
  ];
  return (
    <div className="flex items-center gap-1.5">
      {items.map((it) => (
        <button
          key={it.status}
          type="button"
          onClick={(e) => {
            e.stopPropagation();
            onPick?.(it.status);
          }}
          className={clsx(
            "inline-flex items-center gap-1 rounded-full border-[1.5px] border-ink font-bold transition-colors",
            compact ? "px-2 py-0.5 text-[12px]" : "px-2.5 py-1 text-[13px]",
            active === it.status
              ? "bg-leaf text-ink shadow-chunky-active translate-x-[1px] translate-y-[1px]"
              : "bg-paper text-ink shadow-chunky-sm hover:bg-paper2",
          )}
        >
          <span aria-hidden>{it.emoji}</span>
          <span className="tabular-nums">{it.n}</span>
        </button>
      ))}
    </div>
  );
}
