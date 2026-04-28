import { categoryMeta } from "@/lib/categories";

export function CatTag({
  category,
  displayState,
}: {
  category?: string | null;
  displayState?: string | null;
}) {
  if (displayState === "creating") {
    return (
      <span className="inline-flex items-center gap-1.5 rounded-chip border-[1.5px] border-ink px-3.5 py-1 text-[14px] font-bold shadow-rest">
        ⏳ <span className="uppercase tracking-[0.08em]">creating</span>
      </span>
    );
  }
  if (!category) {
    return (
      <span className="inline-flex items-center gap-1.5 rounded-chip border-[1.5px] border-ink px-3.5 py-1 text-[14px] font-bold shadow-rest">
        ? <span className="uppercase tracking-[0.08em]">unknown</span>
      </span>
    );
  }
  const m = categoryMeta(category);
  return (
    <span
      className="inline-flex items-center gap-1.5 rounded-chip border-[1.5px] border-ink px-3.5 py-1 text-[14px] font-bold shadow-rest"
      style={{ background: m.bg, color: m.ink }}
    >
      {m.image ? (
        // eslint-disable-next-line @next/next/no-img-element
        <img src={m.image} alt="" aria-hidden width={20} height={20} className="shrink-0" />
      ) : m.emoji ? (
        <span aria-hidden className="text-[16px]">{m.emoji}</span>
      ) : null}
      <span
        aria-hidden
        className="h-2.5 w-2.5 rounded-full"
        style={{ background: m.dot }}
      />
      <span className="uppercase tracking-[0.08em]">{m.label}</span>
    </span>
  );
}
