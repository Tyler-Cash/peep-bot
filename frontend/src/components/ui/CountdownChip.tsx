import { countdownLabel } from "@/lib/format";

export function CountdownChip({ iso }: { iso: string }) {
  return (
    <span className="inline-flex items-center rounded-full border-[1.5px] border-ink bg-paper px-2.5 py-0.5 text-[11.5px] font-bold shadow-chunky-sm">
      {countdownLabel(iso)}
    </span>
  );
}
