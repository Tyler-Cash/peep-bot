"use client";

export type SegmentedOption<T> = {
  value: T;
  label: string;
  defaultPill?: boolean;
};

export function SegmentedSelector<T extends string | number>({
  value,
  onChange,
  options,
  ariaLabel,
}: {
  value: T;
  onChange: (v: T) => void;
  options: SegmentedOption<T>[];
  ariaLabel?: string;
}) {
  return (
    <div role="radiogroup" aria-label={ariaLabel} className="flex flex-wrap gap-2">
      {options.map((o) => {
        const active = o.value === value;
        return (
          <button
            key={String(o.value)}
            type="button"
            role="radio"
            aria-checked={active}
            aria-label={o.label}
            onClick={() => onChange(o.value)}
            className={
              "relative px-4 py-2 rounded-chip border-[1.5px] border-ink text-[14px] font-extrabold " +
              (active ? "bg-ink text-paper shadow-rest" : "bg-paper2 text-ink hover:bg-paper3")
            }
          >
            {o.label}
            {o.defaultPill && (
              <span className="absolute -top-2 -right-2 bg-leaf text-ink text-[10px] font-extrabold px-1.5 py-0.5 rounded-chip border-[1.5px] border-ink">
                DEFAULT
              </span>
            )}
          </button>
        );
      })}
    </div>
  );
}
