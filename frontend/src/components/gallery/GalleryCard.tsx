"use client";

import Link from "next/link";
import { Avas } from "@/components/ui/Avas";
import { dateStamp } from "@/lib/format";
import type { GalleryAlbumDto } from "@/lib/types";

export function GalleryCard({ album }: { album: GalleryAlbumDto }) {
  const stamp = dateStamp(album.eventDateTime);

  // The thumbnail acts as the door to the Immich album. The rest of the card
  // navigates to the local event detail page so attendees / RSVPs / chat
  // remain reachable from here.
  const thumbnail = (
    <div className="relative aspect-[4/3] overflow-hidden bg-paper2">
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img
        src={album.thumbnailUrl}
        alt={album.eventName}
        className="w-full h-full object-cover transition-transform duration-300 group-hover:scale-105"
        loading="lazy"
      />
      <span className="absolute bottom-2 right-2 inline-flex items-center gap-1 rounded-chip bg-ink/70 px-2.5 py-1 text-[12px] font-bold text-white backdrop-blur-sm">
        📷 {album.assetCount}
      </span>
    </div>
  );

  return (
    <article className="group flex flex-col overflow-hidden rounded-hero border-[1.5px] border-ink shadow-rest bg-white">
      {album.albumUrl ? (
        <a
          href={album.albumUrl}
          target="_blank"
          rel="noopener noreferrer"
          aria-label={`open ${album.eventName} album in Immich`}
          className="block"
        >
          {thumbnail}
        </a>
      ) : (
        <Link
          href={`/events/${album.eventId}`}
          aria-label={`open ${album.eventName} event`}
          className="block"
        >
          {thumbnail}
        </Link>
      )}

      <Link
        href={`/events/${album.eventId}`}
        className="flex items-end justify-between gap-3 p-4"
      >
        <div className="flex flex-col gap-1 min-w-0">
          <p className="text-[11px] font-extrabold tracking-[0.14em] text-mute uppercase">
            {stamp.month} {stamp.day}
          </p>
          <h2 className="text-[18px] font-extrabold tracking-[-0.02em] leading-[1.2] line-clamp-2">
            {album.eventName}
          </h2>
        </div>
        {(album.attendees ?? []).length > 0 && (
          <Avas people={album.attendees ?? []} max={3} size={26} />
        )}
      </Link>
    </article>
  );
}
