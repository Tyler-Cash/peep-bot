import { categoryMeta } from "@/lib/categories";

const CREATING_STATES = new Set([
  "CREATED",
  "INIT_CHANNEL",
  "INIT_ROLES",
  "CLASSIFY",
  "POST_ALBUM_READY",
  "POST_ALBUM_SHARED",
]);

export function CatTag({
  category,
  state,
}: {
  category?: string | null;
  state?: string | null;
}) {
  if (state && CREATING_STATES.has(state)) {
    return (
        <span className="inline-flex items-center gap-1.5 rounded-full border-[1.5px] border-ink px-3.5 py-1 text-[14px] font-bold shadow-chunky-sm">
        ⏳ <span className="uppercase tracking-[0.08em]">creating</span>
      </span>
    );
  }
  if (!category) {
    return (
        <span className="inline-flex items-center gap-1.5 rounded-full border-[1.5px] border-ink px-3.5 py-1 text-[14px] font-bold shadow-chunky-sm">
        ? <span className="uppercase tracking-[0.08em]">unknown</span>
      </span>
    );
  }
  const m = categoryMeta(category);
  return (
    <span
      className="inline-flex items-center gap-1.5 rounded-full border-[1.5px] border-ink px-3.5 py-1 text-[14px] font-bold shadow-chunky-sm"
      style={{ background: m.bg, color: m.ink }}
    >
      {m.emoji && <span aria-hidden className="text-[16px]">{m.emoji}</span>}
      <span
        aria-hidden
        className="h-2.5 w-2.5 rounded-full"
        style={{ background: m.dot }}
      />
      <span className="uppercase tracking-[0.08em]">{m.label}</span>
    </span>
  );
}
