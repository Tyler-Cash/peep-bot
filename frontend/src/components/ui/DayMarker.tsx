export function DayMarker({ label }: { label: string }) {
  return (
    <div className="flex items-center gap-3 my-5">
      <span className="text-[11px] font-extrabold tracking-[0.18em] text-mute uppercase whitespace-nowrap">
        {label}
      </span>
      <span className="h-px flex-1 bg-ink/15" />
    </div>
  );
}
