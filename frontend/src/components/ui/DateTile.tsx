import clsx from "@/lib/clsx";
import { dateStamp } from "@/lib/format";

type Variant = "responsive" | "mobile" | "desktop";

export function DateTile({
  iso,
  tilt = 0,
  className,
  variant = "responsive",
}: {
  iso: string;
  tilt?: number;
  className?: string;
  variant?: Variant;
}) {
  const stamp = dateStamp(iso);
  const transform = `rotate(${tilt}deg)`;
  const showMobile = variant !== "desktop";
  const showDesktop = variant !== "mobile";
  return (
    <>
      {showMobile && (
        <span
          className={clsx(
            "inline-flex items-stretch border-[1.5px] border-ink rounded-chip shadow-rest overflow-hidden bg-white/95 shrink-0",
            variant === "responsive" && "sm:hidden",
            className,
          )}
          style={{ transform }}
        >
          <span className="flex items-center gap-1 px-2 py-1.5">
            <span className="text-[18px] font-extrabold leading-none tracking-[-0.04em] tabular-nums">
              {stamp.day}
            </span>
            <span className="text-[14px] font-extrabold leading-none tracking-[-0.03em] lowercase">
              {stamp.month.toLowerCase()}
            </span>
          </span>
        </span>
      )}
      {showDesktop && (
        <span
          className={clsx(
            "inline-flex items-stretch border-[1.5px] border-ink rounded-chip shadow-rest overflow-hidden bg-white/95",
            variant === "responsive" && "hidden sm:inline-flex",
            className,
          )}
          style={{ transform }}
        >
          <span className="flex items-center gap-1.5 px-3 py-2">
            <span className="text-[26px] font-extrabold leading-none tracking-[-0.04em] tabular-nums">
              {stamp.day}
            </span>
            <span className="text-[20px] font-extrabold leading-none tracking-[-0.03em] lowercase">
              {stamp.month.toLowerCase()}
            </span>
          </span>
          <span className="flex items-center justify-center px-3 border-l-[1.5px] border-ink bg-white/55">
            <span className="text-[13px] font-extrabold tracking-[0.04em] lowercase leading-none">
              {stamp.weekday}
            </span>
          </span>
        </span>
      )}
    </>
  );
}
