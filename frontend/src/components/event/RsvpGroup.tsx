import { Avatar, type AvatarRef } from "@/components/ui/Avatar";

export function RsvpGroup({
  label,
  emoji,
  people,
}: {
  label: string;
  emoji: string;
  people: AvatarRef[];
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
        {people.map((p, i) => (
          <span
            key={i}
            className="inline-flex items-center gap-1.5 rounded-full border-[1.5px] border-ink bg-paper pl-0.5 pr-2.5 py-0.5"
          >
            <Avatar who={p} size={20} />
            <span className="text-[13px] font-semibold">{p.name}</span>
          </span>
        ))}
      </div>
    </div>
  );
}
