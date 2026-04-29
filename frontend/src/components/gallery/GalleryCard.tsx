"use client";

import Link from "next/link";
import { Avas } from "@/components/ui/Avas";
import { dateStamp, seededTilt } from "@/lib/format";
import type { GalleryAlbumDto } from "@/lib/types";

export function GalleryCard({ album }: { album: GalleryAlbumDto }) {
  const stamp = dateStamp(album.eventDateTime);
  const tilt = seededTilt(album.eventName, 3);
  const dateLine = `${stamp.day} ${stamp.month.toLowerCase()}`;

  // The thumbnail acts as the door to the Immich album. The rest of the card
  // navigates to the local event detail page so attendees / RSVPs / chat
  // remain reachable from here.
  const thumbnail = (
    <div className="relative aspect-square overflow-hidden border-[1.5px] border-ink bg-paper2">
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img
        src={album.thumbnailUrl}
        alt={album.eventName}
        className="w-full h-full object-cover transition-[transform,opacity] duration-200 group-hover/photo:opacity-40 group-hover/photo:scale-[1.02]"
        loading="lazy"
      />
      <span className="absolute right-2 top-2 inline-flex items-center gap-1 rounded-[8px] border-[1.5px] border-ink bg-white/95 px-2 py-[3px] text-[11px] font-extrabold tracking-[-0.01em]">
        📷 {album.assetCount}
      </span>
      <span
        aria-hidden
        className="absolute inset-0 flex items-center justify-center opacity-0 transition-opacity duration-200 group-hover/photo:opacity-100"
      >
        <span className="inline-flex items-center gap-1.5 rounded-chip border-[1.5px] border-ink bg-white px-3 py-1.5 text-[13px] font-extrabold tracking-[-0.01em] shadow-rest">
          open album →
        </span>
      </span>
    </div>
  );

  // Always route the thumbnail click through the BFF /open endpoint — the BFF
  // resolves a fresh per-user 1-week Immich share, then 302-redirects with
  // private cache headers. Constructing the URL from albumId here means the
  // frontend never opens a direct Immich URL even when the backend / mocks
  // happen to surface one.
  const openUrl = `/api/gallery/${encodeURIComponent(album.albumId)}/open`;

  // Polaroid frame: white card, fat bottom band where the event name + date
  // sit (the "hand-written" caption area on a real polaroid). Tilt is hashed
  // off the album id so the same album always tilts the same way — the grid
  // doesn't reshuffle visually when albums load in a different order.
  return (
    <article
      className="group bg-white border-[1.5px] border-ink rounded-[6px] shadow-rest hover:shadow-hero transition-[transform,box-shadow] duration-150 px-[10px] pt-[10px] pb-0"
      style={{ transform: `rotate(${tilt}deg)` }}
    >
      <a
        href={openUrl}
        target="_blank"
        rel="noopener noreferrer"
        aria-label={`open ${album.eventName} album`}
        className="block group/photo"
      >
        {thumbnail}
      </a>

      <Link
        href={`/events/${album.eventId}`}
        className="flex items-end justify-between gap-3 px-1 pt-3.5 pb-4"
      >
        <div className="flex flex-col min-w-0 flex-1">
          <h2 className="text-[18px] font-extrabold tracking-[-0.02em] leading-[1.1] truncate">
            {album.eventName}
          </h2>
          <p className="mt-1 text-[12.5px] font-bold tracking-[0.02em] text-mute">
            {dateLine}
          </p>
        </div>
        {(album.attendees ?? []).length > 0 && (
          <div className="shrink-0">
            <Avas people={album.attendees ?? []} max={3} size={22} />
          </div>
        )}
      </Link>
    </article>
  );
}
