import { Avatar, type AvatarRef } from "./Avatar";

export function Avas({
  people,
  max = 5,
  size = 28,
}: {
  people: AvatarRef[];
  max?: number;
  size?: number;
}) {
  const shown = people.slice(0, max);
  const extra = people.length - shown.length;
  return (
    <div className="flex items-center">
      {shown.map((p, i) => (
        <div key={i} style={{ marginLeft: i === 0 ? 0 : -8 }}>
          <Avatar who={p} size={size} />
        </div>
      ))}
      {extra > 0 && (
        <span
          className="inline-flex items-center justify-center rounded-full border-[1.5px] border-ink bg-paper text-ink text-[11px] font-bold"
          style={{ width: size, height: size, marginLeft: -8 }}
        >
          +{extra}
        </span>
      )}
    </div>
  );
}
