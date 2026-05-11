"use client";

import { useState } from "react";

export type ChipOption = { id: string; name: string };

export function ChipPicker({
  value,
  onChange,
  options,
  disabledIds = [],
  ready,
  label,
  prefix = "@",
}: {
  value: string | null;
  onChange: (id: string) => void;
  options: ChipOption[];
  disabledIds?: (string | null)[];
  ready: boolean;
  label: string;
  prefix?: string;
}) {
  const [open, setOpen] = useState(false);
  const selected = options.find((o) => o.id === value) ?? null;

  if (!ready) {
    return (
      <div>
        <p className="text-[11.5px] font-extrabold tracking-[0.18em] text-mute uppercase">
          ⟳ syncing with discord…
        </p>
        <div className="mt-2 flex gap-2">
          <span className="h-7 w-24 animate-pulse rounded-chip bg-paper2" />
          <span className="h-7 w-20 animate-pulse rounded-chip bg-paper2" />
        </div>
      </div>
    );
  }

  return (
    <div>
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        aria-label={selected ? `${label}: ${selected.name}` : `Select ${label}`}
        className="px-3 py-2 rounded-chip border-[1.5px] border-ink bg-paper2 text-[14px] font-extrabold shadow-rest"
      >
        {selected ? `${prefix}${selected.name}` : `select ${label.toLowerCase()}…`}
      </button>
      {open && (
        <ul className="mt-2 flex flex-wrap gap-2">
          {options.map((o) => {
            const disabled = disabledIds.includes(o.id);
            return (
              <li key={o.id}>
                <button
                  type="button"
                  disabled={disabled}
                  onClick={() => {
                    if (disabled) return;
                    onChange(o.id);
                    setOpen(false);
                  }}
                  className={
                    "px-3 py-1.5 rounded-chip border-[1.5px] border-ink text-[13.5px] font-extrabold " +
                    (disabled
                      ? "bg-paper3 text-mute line-through cursor-not-allowed"
                      : "bg-paper hover:bg-paper2")
                  }
                >
                  {o.name}
                </button>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
