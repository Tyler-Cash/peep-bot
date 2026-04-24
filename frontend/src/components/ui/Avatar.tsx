import clsx from "@/lib/clsx";
import { initials } from "@/lib/format";

export type AvatarRef = {
  name: string;
  hue?: string;
  avatarUrl?: string | null;
};

export function Avatar({
  who,
  size = 32,
  className,
}: {
  who: AvatarRef;
  size?: number;
  className?: string;
}) {
  const bg = who.hue ?? "#7BC24F";
  if (who.avatarUrl) {
    return (
      // eslint-disable-next-line @next/next/no-img-element
      <img
        src={who.avatarUrl}
        alt={who.name}
        width={size}
        height={size}
        className={clsx(
          "rounded-full border-[1.5px] border-ink object-cover",
          className,
        )}
        style={{ width: size, height: size }}
      />
    );
  }
  return (
    <span
      className={clsx(
        "inline-flex items-center justify-center rounded-full border-[1.5px] border-ink font-bold text-ink text-[11px] leading-none select-none",
        className,
      )}
      style={{ width: size, height: size, background: bg }}
      aria-label={who.name}
    >
      {initials(who.name)}
    </span>
  );
}
