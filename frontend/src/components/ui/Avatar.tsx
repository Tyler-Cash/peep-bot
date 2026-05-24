import Image from "next/image";
import clsx from "@/lib/clsx";
import { initials, stringToColor } from "@/lib/format";
import { useState } from "react";

export type AvatarRef = {
  name?: string | null;
  username?: string | null;
  hue?: string;
  avatarUrl?: string | null;
};

const loadedUrls = new Set<string>();
const failedUrls = new Set<string>();

export function Avatar({
  who,
  size = 32,
  className,
}: {
  who: AvatarRef;
  size?: number;
  className?: string;
}) {
  const [imgLoaded, setImgLoaded] = useState(
    () => !!who.avatarUrl && loadedUrls.has(who.avatarUrl),
  );
  const [imgFailed, setImgFailed] = useState(
    () => !!who.avatarUrl && failedUrls.has(who.avatarUrl),
  );
  const name = who.name ?? "";
  const bg = who.hue ?? (name ? stringToColor(name) : "hsl(0,0%,85%)");

  // Re-sync load/fail state against the module-level caches whenever the avatar
  // URL changes. Done during render via a prev-value tracker rather than an
  // effect (react-hooks/set-state-in-effect), which also avoids a flash of the
  // initials before the cached image state is reapplied.
  const [trackedUrl, setTrackedUrl] = useState(who.avatarUrl);
  if (who.avatarUrl !== trackedUrl) {
    setTrackedUrl(who.avatarUrl);
    setImgLoaded(!!who.avatarUrl && loadedUrls.has(who.avatarUrl));
    setImgFailed(!!who.avatarUrl && failedUrls.has(who.avatarUrl));
  }

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
        // `unoptimized` keeps the browser fetching the BFF route directly so
        // the user's SESSION cookie flows through and Vercel's `/_next/image`
        // edge cache (which has no per-user key) stays out of the path.
        <Image
          src={who.avatarUrl}
          alt={name}
          width={size}
          height={size}
          unoptimized
          className={clsx(
            "absolute inset-0 w-full h-full object-cover transition-opacity duration-150",
            imgLoaded ? "opacity-100" : "opacity-0",
          )}
          onLoad={() => {
            if (who.avatarUrl) loadedUrls.add(who.avatarUrl);
            setImgLoaded(true);
          }}
          onError={() => {
            if (who.avatarUrl) failedUrls.add(who.avatarUrl);
            setImgFailed(true);
          }}
        />
      )}
    </span>
  );
}
