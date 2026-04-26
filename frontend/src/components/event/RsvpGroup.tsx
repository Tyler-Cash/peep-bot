import { Avatar } from "@/components/ui/Avatar";
import clsx from "@/lib/clsx";
import type { Attendee } from "@/lib/types";

export function RsvpGroup({
  label,
  emoji,
  people,
  onRemove,
}: {
  label: string;
  emoji: string;
  people: Attendee[];
  onRemove?: (attendee: Attendee) => void;
}) {
  return (
    <div className="flex items-start gap-3">
      <span className="inline-flex items-center gap-1 rounded-full border-[1.5px] border-ink bg-paper px-2.5 py-0.5 text-[11.5px] font-extrabold shadow-chunky-sm shrink-0">
        <span aria-hidden>{emoji}</span>
        <span className="uppercase tracking-[0.08em]">
          {label} · {people.length}
        </span>
      </span>
      <div className="flex flex-wrap gap-1.5">
        {people.length === 0 && <span className="text-[13px] text-mute">nobody yet</span>}
        {people.map((p, i) =>
          onRemove ? (
            <button
              key={i}
              type="button"
              onClick={() => onRemove(p)}
              className="group inline-flex items-center gap-1.5 rounded-full border-[1.5px] border-ink bg-paper pl-0.5 pr-2.5 py-0.5 hover:border-rose-400 transition-colors cursor-pointer"
            >
              <span className="relative shrink-0 inline-flex items-center justify-center" style={{ width: 20, height: 20 }}>
                <Avatar
                  who={p}
                  size={20}
                  className="group-hover:opacity-25 transition-opacity"
                />
                <span className="absolute inset-0 flex items-center justify-center opacity-0 group-hover:opacity-100 transition-opacity text-[11px] font-black text-rose-600">
                  ✕
                </span>
              </span>
              <span className={clsx("text-[13px] font-semibold group-hover:text-rose-600 transition-colors")}>
                {p.name}
              </span>
            </button>
          ) : (
            <span
              key={i}
              className="inline-flex items-center gap-1.5 rounded-full border-[1.5px] border-ink bg-paper pl-0.5 pr-2.5 py-0.5"
            >
              <Avatar who={p} size={20} />
              <span className="text-[13px] font-semibold">{p.name}</span>
            </span>
          ),
        )}
      </div>
    </div>
  );
}
