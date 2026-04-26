import clsx from "@/lib/clsx";
import { initials, stringToColor } from "@/lib/format";
import { useEffect, useState } from "react";

export type AvatarRef = {
  name?: string | null;
  username?: string | null;
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
  const [imgLoaded, setImgLoaded] = useState(false);
  const [imgFailed, setImgFailed] = useState(false);
  const name = who.name ?? "";
  const bg = who.hue ?? (name ? stringToColor(name) : "hsl(0,0%,85%)");

  useEffect(() => {
    setImgLoaded(false);
    setImgFailed(false);
  }, [who.avatarUrl]);

  return (
    <span
      className={clsx(
        "relative inline-flex items-center justify-center rounded-full border-[1.5px] border-ink font-bold text-ink leading-none select-none overflow-hidden",
        className,
      )}
      style={{ width: size, height: size, background: bg, fontSize: Math.round(size * 0.42) }}
      aria-label={name}
    >
      {name ? initials(name) : null}
      {who.avatarUrl && !imgFailed && (
        // eslint-disable-next-line @next/next/no-img-element
        <img
          src={who.avatarUrl}
          alt={name}
          width={size}
          height={size}
          className={clsx(
            "absolute inset-0 w-full h-full object-cover transition-opacity duration-150",
            imgLoaded ? "opacity-100" : "opacity-0",
          )}
          onLoad={() => setImgLoaded(true)}
          onError={() => setImgFailed(true)}
        />
      )}
    </span>
  );
}
