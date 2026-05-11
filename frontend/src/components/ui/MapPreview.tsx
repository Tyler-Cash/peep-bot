export function MapPreview({ label }: { label: string }) {
  return (
    <div className="bg-paper2 border-b-[1.5px] border-ink flex flex-col items-center justify-center py-10">
      <span className="text-[40px]" role="img" aria-label="location pin">
        📍
      </span>
      <span className="mt-2 text-[12.5px] font-semibold text-mute lowercase">
        {label || "no location set"}
      </span>
    </div>
  );
}
