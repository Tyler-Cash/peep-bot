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
    <div className="mx-auto max-w-[1200px] px-4 sm:px-5 py-6">
      <header className="mb-5 flex items-start justify-between gap-4 flex-wrap">
        <div className="min-w-0">
          <h1 className="text-[40px] sm:text-[54px] font-extrabold tracking-[-0.04em] leading-[0.95]">
            the gallery
          </h1>
          <p className="mt-1.5 max-w-[560px] text-[15px] sm:text-[17px] font-medium leading-[1.4] text-mute">
            drop your pics in the event channel after the event and peepbot will record it.
          </p>
        </div>
        <span
          className="inline-flex items-center gap-1.5 shrink-0 rounded-chip border-[1.5px] border-ink bg-leaf px-3 sm:px-4 py-1.5 text-[13px] sm:text-[15px] font-extrabold shadow-rest"
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
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-x-6 gap-y-7 px-1 pt-1.5 pb-8">
          {albums!.map((album) => (
            <GalleryCard key={album.albumId} album={album} />
          ))}
        </div>
      )}
    </div>
  );
}
