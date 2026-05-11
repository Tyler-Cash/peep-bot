"use client";

import { LocationAutocomplete } from "@/components/ui/LocationAutocomplete";
import { MapPreview } from "@/components/ui/MapPreview";

export function PrimaryLocationCard({
  value,
  onChange,
  onPick,
  locationBias,
}: {
  value: string;
  onChange: (v: string) => void;
  onPick: (placeId: string, display: string) => void;
  locationBias?: { lat: number; lng: number };
}) {
  return (
    <section className="bg-white border-[1.5px] border-ink rounded-[16px] shadow-[4px_4px_0_#0E100D] overflow-hidden">
      <MapPreview label={value} />
      <div className="px-[22px] py-[18px]">
        <h2 className="text-[24px] font-extrabold tracking-[-0.03em] lowercase">primary location</h2>
        <p className="text-[13.5px] font-semibold text-mute mt-1">
          biases venue search toward your group&apos;s area
        </p>
        <div className="mt-3">
          <LocationAutocomplete
            value={value}
            onChange={onChange}
            onPick={onPick}
            placeholder="e.g. Melbourne, VIC"
            locationBias={locationBias}
          />
          <p className="text-[12.5px] font-semibold text-mute mt-1">
            start typing to search google places.
          </p>
        </div>
      </div>
    </section>
  );
}
