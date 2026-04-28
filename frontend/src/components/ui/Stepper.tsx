"use client";

type Props = {
  value: number;
  onChange: (n: number) => void;
  min?: number;
  max?: number;
  // value === 0 renders as the placeholder ("unlimited") rather than a 0
  zeroAsPlaceholder?: boolean;
  placeholder?: string;
};

export function Stepper({
  value,
  onChange,
  min = 0,
  max = 9999,
  zeroAsPlaceholder = true,
  placeholder = "∞",
}: Props) {
  const clamp = (n: number) => Math.max(min, Math.min(max, n));
  const display = zeroAsPlaceholder && value === 0 ? "" : String(value);

  return (
    <div className="flex items-stretch h-12 rounded-chip border-[1.5px] border-ink bg-white shadow-rest overflow-hidden">
      <button
        type="button"
        aria-label="decrease"
        onClick={() => onChange(clamp(value - 1))}
        disabled={value <= min}
        className="w-[42px] shrink-0 bg-paper hover:bg-white border-r-[1.5px] border-ink text-[20px] font-extrabold text-ink leading-none disabled:opacity-40 disabled:cursor-not-allowed"
      >
        −
      </button>
      <div className="relative flex-1 min-w-0">
        <input
          type="number"
          inputMode="numeric"
          value={display}
          onChange={(e) => {
            const v = e.target.value;
            onChange(v === "" ? 0 : clamp(Number(v)));
          }}
          className="w-full h-full text-center text-[17px] font-extrabold text-ink bg-transparent outline-none [-moz-appearance:textfield] [&::-webkit-inner-spin-button]:appearance-none [&::-webkit-outer-spin-button]:appearance-none"
        />
        {display === "" && (
          <span
            aria-hidden
            className="pointer-events-none absolute inset-0 flex items-center justify-center text-[22px] font-extrabold text-mute leading-none"
          >
            {placeholder}
          </span>
        )}
      </div>
      <button
        type="button"
        aria-label="increase"
        onClick={() => onChange(clamp((value || 0) + 1))}
        className="w-[42px] shrink-0 bg-paper hover:bg-white border-l-[1.5px] border-ink text-[20px] font-extrabold text-ink leading-none"
      >
        +
      </button>
    </div>
  );
}
