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
  const items: Array<{ status: RsvpStatus; image: string; alt: string; n: number }> = [
    { status: "going", image: "/peepos/peepo-going.png", alt: "going", n: counts.going },
    { status: "maybe", image: "/peepos/peepo-maybe.png", alt: "maybe", n: counts.maybe },
    { status: "declined", image: "/peepos/peepo-no.png", alt: "can't make it", n: counts.declined },
  ];
  const imgSize = compact ? 18 : 22;
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
            "inline-flex items-center gap-1.5 rounded-full border-[1.5px] border-ink font-bold transition-colors",
            compact ? "px-2.5 py-1 text-[14px]" : "px-3.5 py-1.5 text-[16px]",
            active === it.status
              ? "bg-leaf text-ink shadow-chunky-active translate-x-[1px] translate-y-[1px]"
              : "bg-paper text-ink shadow-chunky-sm hover:bg-paper2",
          )}
        >
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src={it.image} alt={it.alt} width={imgSize} height={imgSize} className="shrink-0" />
          <span className="tabular-nums">{it.n}</span>
        </button>
      ))}
    </div>
  );
}
