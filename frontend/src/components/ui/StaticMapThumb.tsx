"use client";

import { useState } from "react";
import clsx from "@/lib/clsx";
import { PinIcon } from "@/components/icons/PinIcon";

type Size = 52 | 72 | 180;

const PIN_BY_SIZE: Record<Size, number> = {
  52: 17,
  72: 23,
  180: 42,
};

export function StaticMapThumb({
  placeId,
  size = 52,
  rounded = "rounded-[8px]",
  className,
  zoom,
}: {
  placeId?: string | null;
  size?: Size;
  rounded?: string;
  className?: string;
  zoom?: number;
}) {
  const [errored, setErrored] = useState(false);
  const showImage = placeId && !errored;
  const pinSize = PIN_BY_SIZE[size];

  return (
    <div
      className={clsx(
        "relative shrink-0 overflow-hidden border-[1.5px] border-ink bg-[#F2EFE6]",
        rounded,
        className,
      )}
      style={{ width: size, height: size }}
      aria-hidden
    >
      {showImage ? (
        // eslint-disable-next-line @next/next/no-img-element -- dynamic proxied static-map; next/image not needed for a 52–72px thumb
        <img
          src={`/api/places/staticmap?placeId=${encodeURIComponent(placeId)}&size=${size}${
            zoom ? `&zoom=${zoom}` : ""
          }`}
          alt=""
          width={size}
          height={size}
          className="absolute inset-0 w-full h-full object-cover"
          onError={() => setErrored(true)}
        />
      ) : null}
      <span
        className="absolute left-1/2 top-1/2 pointer-events-none"
        style={{ transform: "translate(-50%, -85%)" }}
      >
        {showImage ? (
          <PinIcon size={pinSize} />
        ) : (
          <span
            className="block text-center"
            style={{
              fontSize: Math.round(size * 0.42),
              transform: "translateY(15%)",
            }}
          >
            📍
          </span>
        )}
      </span>
    </div>
  );
}
