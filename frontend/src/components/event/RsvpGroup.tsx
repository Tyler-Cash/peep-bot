import { Avatar } from "@/components/ui/Avatar";
import clsx from "@/lib/clsx";
import type { Attendee } from "@/lib/types";

export function RsvpGroup({
  label,
  emoji,
  image,
  people,
  onRemove,
}: {
  label: string;
  emoji: string;
  image?: string;
  people: Attendee[];
  onRemove?: (attendee: Attendee) => void;
}) {
  return (
    <div className="flex items-start gap-3">
      <span className="inline-flex items-center gap-1.5 rounded-full border-[1.5px] border-ink bg-paper px-3 py-1.5 text-[15px] font-extrabold shadow-chunky-sm shrink-0">
        {image ? (
          // eslint-disable-next-line @next/next/no-img-element
          <img src={image} alt="" aria-hidden width={22} height={22} className="shrink-0" />
        ) : (
          <span aria-hidden className="text-[18px]">{emoji}</span>
        )}
        <span className="uppercase tracking-[0.08em] pt-0.5">
          {label} · {people.length}
        </span>
      </span>
      <div className="flex flex-wrap gap-1.5">
        {people.length === 0 && <span className="text-[15px] text-mute">nobody yet</span>}
        {people.map((p, i) =>
          onRemove ? (
              <button
              key={i}
              type="button"
              onClick={() => onRemove(p)}
              className="group inline-flex items-center gap-2 rounded-full border-[1.5px] border-ink bg-paper pl-1 pr-3 py-1 hover:border-rose-400 transition-colors cursor-pointer"
            >
              <span className="relative shrink-0 inline-flex items-center justify-center" style={{ width: 28, height: 28 }}>
                <Avatar
                  who={p}
                  size={28}
                  className="group-hover:opacity-25 transition-opacity"
                />
                <span className="absolute inset-0 flex items-center justify-center opacity-0 group-hover:opacity-100 transition-opacity text-[15px] font-black text-rose-600">
                  ✕
                </span>
              </span>
              <span className={clsx("text-[15px] font-semibold group-hover:text-rose-600 transition-colors")}>
                {p.name}
              </span>
            </button>
          ) : (
            <span
              key={i}
              className="inline-flex items-center gap-2 rounded-full border-[1.5px] border-ink bg-paper pl-1 pr-3 py-1"
            >
              <Avatar who={p} size={28} />
              <span className="text-[15px] font-semibold">{p.name}</span>
            </span>
          ),
        )}
      </div>
    </div>
  );
}
