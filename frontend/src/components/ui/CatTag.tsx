import { categoryMeta } from "@/lib/categories";
import type { Category } from "@/lib/types";

export function CatTag({ category }: { category: Category }) {
  const m = categoryMeta(category);
  return (
    <span
      className="inline-flex items-center gap-1.5 rounded-full border-[1.5px] border-ink px-2.5 py-0.5 text-[11.5px] font-bold shadow-chunky-sm"
      style={{ background: m.bg, color: m.ink }}
    >
      <span aria-hidden>{m.emoji}</span>
      <span className="uppercase tracking-[0.08em]">{m.label}</span>
    </span>
  );
}
