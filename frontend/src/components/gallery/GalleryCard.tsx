"use client";

import Link from "next/link";
import { dateStamp } from "@/lib/format";
import type { GalleryAlbumDto } from "@/lib/types";

export function GalleryCard({ album }: { album: GalleryAlbumDto }) {
  const stamp = dateStamp(album.eventDateTime);

  return (
    <Link
      href={`/events/${album.eventId}`}
      className="group flex flex-col overflow-hidden rounded-[16px] border-[1.5px] border-ink shadow-chunky-sm hover:shadow-chunky-md transition-shadow bg-white"
    >
      <div className="relative aspect-[4/3] overflow-hidden bg-paper2">
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img
          src={album.thumbnailUrl}
          alt={album.eventName}
          className="w-full h-full object-cover transition-transform duration-300 group-hover:scale-105"
          loading="lazy"
        />
        <span className="absolute bottom-2 right-2 inline-flex items-center gap-1 rounded-full bg-ink/70 px-2.5 py-1 text-[12px] font-bold text-white backdrop-blur-sm">
          📷 {album.assetCount}
        </span>
      </div>

      <div className="p-4 flex flex-col gap-1">
        <p className="text-[11px] font-extrabold tracking-[0.14em] text-mute uppercase">
          {stamp.month} {stamp.day}
        </p>
        <h2 className="text-[18px] font-extrabold tracking-[-0.02em] leading-[1.2] line-clamp-2">
          {album.eventName}
        </h2>
      </div>
    </Link>
  );
}
