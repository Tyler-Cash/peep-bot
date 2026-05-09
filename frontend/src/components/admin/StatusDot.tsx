import clsx from "@/lib/clsx";

const COLORS: Record<string, string> = {
  ok: "bg-[#5FA838]",
  warn: "bg-[#E07A2B]",
  fail: "bg-[#DC2626]",
  muted: "bg-mute",
};

export function StatusDot({
  status,
  size = 12,
  className,
}: {
  status: string | null | undefined;
  size?: number;
  className?: string;
}) {
  const color = COLORS[status ?? "muted"] ?? COLORS.muted;
  return (
    <span
      aria-hidden
      className={clsx(
        "inline-block rounded-full border-[1.5px] border-ink shrink-0",
        color,
        className,
      )}
      style={{ width: size, height: size }}
    />
  );
}
