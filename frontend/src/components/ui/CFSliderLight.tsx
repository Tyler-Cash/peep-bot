"use client";

export function CFSliderLight({
  value,
  min = 1,
  max = 10,
  onChange,
  disabled = false,
}: {
  value: number;
  min?: number;
  max?: number;
  onChange: (v: number) => void;
  disabled?: boolean;
}) {
  const ticks: number[] = [];
  for (let i = min; i <= max; i++) ticks.push(i);

  return (
    <div className="select-none">
      <input
        type="range"
        min={min}
        max={max}
        step={1}
        value={value}
        disabled={disabled}
        onChange={(e) => onChange(Number(e.target.value))}
        aria-valuenow={value}
        aria-valuemin={min}
        aria-valuemax={max}
        className="w-full accent-leaf"
      />
      <div className="mt-2 flex justify-between font-extrabold text-[13px] tabular-nums">
        {ticks.map((n) => (
          <span
            key={n}
            data-testid={`tick-${n}`}
            className={n === value ? "text-leafDk" : "text-ink"}
          >
            {n}
          </span>
        ))}
      </div>
    </div>
  );
}
