"use client";

import { PeepoSleep } from "@/components/Peepo";
import { useGallery } from "@/lib/hooks";
import { GalleryCard } from "./GalleryCard";

export function GalleryFeed() {
  const { data: albums, error, isLoading } = useGallery();

  if (isLoading) {
    return (
      <div className="mx-auto max-w-[980px] px-5 py-6">
        <p className="text-center text-mute text-[14px]">loading albums…</p>
      </div>
    );
  }

  if (error) {
    return (
      <div className="mx-auto max-w-[980px] px-5 py-6 flex flex-col items-center gap-3 text-center">
        <PeepoSleep size={90} />
        <p className="text-mute text-[14px]">can&apos;t load gallery right now.</p>
      </div>
    );
  }

  const count = albums?.length ?? 0;

  return (
    <div className="mx-auto max-w-[980px] px-5 py-6">
      <header className="flex items-end justify-between mb-5">
        <h1 className="text-[54px] sm:text-[64px] font-extrabold tracking-[-0.04em] leading-[0.95]">
          gallery
        </h1>
        <span
          className="inline-flex items-center gap-1.5 rounded-chip border-[1.5px] border-ink bg-leaf px-4 py-1.5 text-[15px] font-extrabold shadow-rest"
          style={{ transform: "rotate(-2deg)" }}
        >
          {count} {count === 1 ? "album" : "albums"} 📷
        </span>
      </header>

      {count === 0 ? (
        <div className="mt-16 flex flex-col items-center gap-3 text-center">
          <PeepoSleep size={90} />
          <p className="text-[18px] font-semibold text-mute">no albums yet</p>
          <p className="text-[14px] text-mute">albums appear here after events you attended get photos uploaded.</p>
        </div>
      ) : (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-5 mt-2">
          {albums!.map((album) => (
            <GalleryCard key={album.albumId} album={album} />
          ))}
        </div>
      )}
    </div>
  );
}
