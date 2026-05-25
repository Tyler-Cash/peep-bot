"use client";

import { useState } from "react";
import { PinIcon } from "@/components/icons/PinIcon";

export function SidebarMapCard({
  location,
  placeId,
  subtitle,
}: {
  location: string;
  placeId?: string | null;
  subtitle?: string;
}) {
  const [errored, setErrored] = useState(false);
  const mapsHref = `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(
    location,
  )}${placeId ? `&query_place_id=${encodeURIComponent(placeId)}` : ""}`;
  const showImage = placeId && !errored;

  return (
    <div className="rounded-card border-[1.5px] border-ink bg-white p-3.5 shadow-rest flex flex-col gap-2.5">
      <div className="flex items-center justify-between">
        <span className="text-[13px] font-extrabold tracking-[0.18em] text-mute uppercase">
          where
        </span>
        <span
          className="text-[10.5px] font-extrabold tracking-[0.12em] text-leafDk bg-leafLt border-[1.5px] border-ink rounded-full px-[7px] py-px uppercase"
        >
          new
        </span>
      </div>

      <a
        href={mapsHref}
        target="_blank"
        rel="noreferrer"
        className="relative block rounded-[10px] overflow-hidden bg-[#F2EFE6]"
        style={{ height: 180 }}
      >
        {showImage && (
          // eslint-disable-next-line @next/next/no-img-element -- proxied static-map; next/image not needed
          <img
            src={`/api/places/staticmap?placeId=${encodeURIComponent(placeId)}&size=180&zoom=15`}
            alt=""
            className="absolute inset-0 w-full h-full object-cover"
            onError={() => setErrored(true)}
          />
        )}
        <span
          className="absolute left-1/2 top-1/2 pointer-events-none"
          style={{ transform: "translate(-50%, -85%)" }}
          aria-hidden
        >
          <PinIcon size={42} />
        </span>

        <span
          className="absolute top-2.5 right-2.5 inline-flex items-center gap-1 rounded-chip border-[1.5px] border-ink bg-white/95 px-3 py-1.5 text-[14px] font-extrabold tracking-[-0.01em] text-ink shadow-rest"
        >
          open in maps →
        </span>

        <span
          className="absolute bottom-2.5 left-2.5 inline-flex items-center gap-2 bg-white/95 border-[1.5px] border-ink rounded-[6px] px-2.5 py-[3px] text-[12px] font-extrabold tracking-[0.08em] text-ink uppercase"
        >
          <span className="block bg-ink" style={{ width: 24, height: 3 }} />
          200 m
        </span>
      </a>

      <div className="flex flex-col gap-1">
        <span className="text-[17px] font-extrabold tracking-[-0.01em] leading-[1.15] text-ink break-words">
          {location}
        </span>
        {subtitle && (
          <span className="text-[13.5px] font-semibold leading-[1.35] text-mute">
            {subtitle}
          </span>
        )}
      </div>
    </div>
  );
}
