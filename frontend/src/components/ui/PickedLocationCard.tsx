"use client";

import type { PlaceSuggestion } from "@/lib/places";
import { StaticMapThumb } from "@/components/ui/StaticMapThumb";

export function PickedLocationCard({
  place,
  onClear,
}: {
  place: PlaceSuggestion;
  onClear: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClear}
      title="click to change"
      className="w-full flex items-center gap-3.5 p-3 rounded-chip border-[1.5px] border-ink bg-white hover:bg-paper2 shadow-rest transition-colors text-left"
    >
      <StaticMapThumb placeId={place.id} size={72} />
      <span className="flex flex-col min-w-0 gap-[2px] flex-1">
        <span className="text-[11px] font-extrabold tracking-[0.14em] uppercase text-leafDk">
          ✓ picked
        </span>
        <span className="text-[17px] font-extrabold tracking-[-0.01em] leading-[1.15] text-ink truncate">
          {place.title}
        </span>
        {place.subtitle && (
          <span className="text-[14px] font-semibold leading-[1.3] text-mute truncate">
            {place.subtitle}
          </span>
        )}
      </span>
      <span
        aria-hidden
        className="shrink-0 px-2 py-1.5 text-[13px] font-extrabold tracking-[0.12em] uppercase text-mute"
      >
        × change
      </span>
    </button>
  );
}
