import { countdownLabel } from "@/lib/format";

export function CountdownChip({ iso }: { iso: string }) {
  return (
    <span className="inline-flex items-center rounded-chip border-[1.5px] border-ink bg-paper px-3.5 py-1 text-[14px] font-bold shadow-rest">
      {countdownLabel(iso)}
    </span>
  );
}
