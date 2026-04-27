"use client";

import { useEffect, useRef, useState } from "react";
import clsx from "@/lib/clsx";

type Props = {
  value: string | null; // "YYYY-MM-DDTHH:mm" — same shape datetime-local emits
  onChange: (next: string) => void;
};

function toISO(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

const TIMES: [number, number][] = [];
for (let h = 8; h <= 22; h++) {
  TIMES.push([h, 0]);
  TIMES.push([h, 30]);
}

function slotLabel(h: number, m: number): string {
  return new Date(2000, 0, 1, h, m)
    .toLocaleString("en", { hour: "numeric", minute: "2-digit" })
    .toLowerCase()
    .replace(" ", "");
}

const navBtnCls =
  "w-[26px] h-[26px] bg-paper2 dark:bg-white/[0.06] border border-line dark:border-lineDk rounded-[8px] text-[14px] font-extrabold text-ink dark:text-paper inline-flex items-center justify-center cursor-pointer";

export function DateTimePicker({ value, onChange }: Props) {
  const parsed = value ? new Date(value) : null;
  const today = new Date();
  const startOfToday = new Date(
    today.getFullYear(),
    today.getMonth(),
    today.getDate(),
  );

  const [open, setOpen] = useState(false);
  const [viewYear, setViewYear] = useState(
    parsed?.getFullYear() ?? today.getFullYear(),
  );
  const [viewMonth, setViewMonth] = useState(
    parsed?.getMonth() ?? today.getMonth(),
  );

  const wrapRef = useRef<HTMLDivElement | null>(null);
  const timeListRef = useRef<HTMLDivElement | null>(null);

  // Sync viewed month to current value (handles external changes like initial data load)
  useEffect(() => {
    if (value) {
      const d = new Date(value);
      setViewYear(d.getFullYear());
      setViewMonth(d.getMonth());
    }
  }, [value]);

  useEffect(() => {
    const onDoc = (e: MouseEvent) => {
      if (wrapRef.current && !wrapRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener("mousedown", onDoc);
    return () => document.removeEventListener("mousedown", onDoc);
  }, []);

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") setOpen(false);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open]);

  // Scroll selected time slot into view when the popover opens
  useEffect(() => {
    if (!open || !timeListRef.current) return;
    const selected = timeListRef.current.querySelector('[aria-pressed="true"]');
    selected?.scrollIntoView({ block: "center" });
  }, [open]);

  const displayLabel = parsed
    ? new Date(value!)
        .toLocaleString("en", {
          weekday: "short",
          day: "numeric",
          month: "short",
          year: "numeric",
          hour: "numeric",
          minute: "2-digit",
        })
        .toLowerCase()
    : null;

  const firstDow = new Date(viewYear, viewMonth, 1).getDay();
  const daysInMonth = new Date(viewYear, viewMonth + 1, 0).getDate();
  const cells: (number | null)[] = [
    ...Array(firstDow).fill(null),
    ...Array.from({ length: daysInMonth }, (_, i) => i + 1),
  ];

  const pickDate = (day: number) => {
    const cur = parsed ?? new Date(viewYear, viewMonth, day, 18, 30);
    const next = new Date(
      viewYear,
      viewMonth,
      day,
      cur.getHours(),
      cur.getMinutes(),
    );
    onChange(toISO(next));
  };

  const pickTime = (h: number, m: number) => {
    const base = parsed ?? new Date(viewYear, viewMonth, 1);
    const next = new Date(
      base.getFullYear(),
      base.getMonth(),
      base.getDate(),
      h,
      m,
    );
    onChange(toISO(next));
  };

  const prevMonth = () => {
    if (viewMonth === 0) {
      setViewYear(viewYear - 1);
      setViewMonth(11);
    } else {
      setViewMonth(viewMonth - 1);
    }
  };

  const nextMonth = () => {
    if (viewMonth === 11) {
      setViewYear(viewYear + 1);
      setViewMonth(0);
    } else {
      setViewMonth(viewMonth + 1);
    }
  };

  const monthLabel = new Date(viewYear, viewMonth, 1).toLocaleString("en", {
    month: "long",
    year: "numeric",
  });

  return (
    <div ref={wrapRef} className="relative w-full">
      <button
        type="button"
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-controls="dtp-popover"
        onClick={() => setOpen((o) => !o)}
        className="w-full rounded-[10px] border-[1.5px] border-ink bg-paper2 px-4 py-[13px] text-[15.5px] flex items-center gap-[10px] text-left cursor-pointer shadow-chunky-sm focus-visible:outline-none focus-visible:shadow-chunky-md"
      >
        <span className="text-[15px] leading-none" aria-hidden>
          📅
        </span>
        <span
          className={clsx(
            "flex-1",
            parsed ? "font-semibold text-ink" : "font-medium text-mute",
          )}
        >
          {displayLabel ?? "pick a date & time"}
        </span>
        <span
          className="text-[10px] text-mute transition-transform duration-[120ms]"
          style={{ transform: open ? "rotate(180deg)" : undefined }}
          aria-hidden
        >
          ▾
        </span>
      </button>

      {open && (
        <div
          id="dtp-popover"
          role="dialog"
          aria-label="pick date and time"
          className="absolute top-[calc(100%+8px)] left-0 z-10 grid grid-cols-[260px_120px] bg-white dark:bg-ink3 border-[1.5px] border-ink dark:border-[#3A3F36] rounded-[14px] shadow-chunky-md dark:shadow-[4px_4px_0_#000] overflow-hidden"
        >
          {/* Calendar pane */}
          <div className="border-r border-line dark:border-lineDk pt-[14px] px-[14px] pb-[12px]">
            {/* Month header */}
            <div className="flex items-center justify-between mb-[10px]">
              <button
                type="button"
                onClick={prevMonth}
                aria-label="previous month"
                className={navBtnCls}
              >
                ‹
              </button>
              <span className="text-[13px] font-extrabold tracking-[-0.2px] text-ink dark:text-paper">
                {monthLabel}
              </span>
              <button
                type="button"
                onClick={nextMonth}
                aria-label="next month"
                className={navBtnCls}
              >
                ›
              </button>
            </div>

            {/* Weekday headers */}
            <div className="grid grid-cols-7 gap-[2px] mb-1">
              {["S", "M", "T", "W", "T", "F", "S"].map((d, i) => (
                <div
                  key={i}
                  className="text-center text-[10px] font-extrabold tracking-[1px] text-mute dark:text-muteDk py-1"
                >
                  {d}
                </div>
              ))}
            </div>

            {/* Day grid */}
            <div className="grid grid-cols-7 gap-[2px]">
              {cells.map((day, i) => {
                if (day === null) return <div key={`e${i}`} />;

                const cellDate = new Date(viewYear, viewMonth, day);
                const isPast = cellDate < startOfToday;
                const isToday =
                  cellDate.getFullYear() === today.getFullYear() &&
                  cellDate.getMonth() === today.getMonth() &&
                  cellDate.getDate() === today.getDate();
                const isSelected =
                  parsed !== null &&
                  parsed.getFullYear() === viewYear &&
                  parsed.getMonth() === viewMonth &&
                  parsed.getDate() === day;

                return (
                  <button
                    key={day}
                    type="button"
                    disabled={isPast}
                    onClick={() => pickDate(day)}
                    aria-label={cellDate.toLocaleDateString("en", {
                      dateStyle: "full",
                    })}
                    aria-pressed={isSelected}
                    className={clsx(
                      "aspect-square rounded-[8px] text-[12px] font-bold p-0 border-[1.5px] transition-colors",
                      isSelected
                        ? "bg-leaf border-ink shadow-chunky-sm -translate-x-px -translate-y-px text-ink cursor-pointer"
                        : isToday
                          ? "bg-paper2 dark:bg-white/[0.06] border-transparent text-ink dark:text-paper cursor-pointer hover:bg-paper3 dark:hover:bg-white/[0.1]"
                          : isPast
                            ? "bg-transparent border-transparent text-mute dark:text-muteDk opacity-35 cursor-not-allowed"
                            : "bg-transparent border-transparent text-ink dark:text-paper cursor-pointer hover:bg-paper2 dark:hover:bg-white/[0.06]",
                    )}
                  >
                    {day}
                  </button>
                );
              })}
            </div>
          </div>

          {/* Time pane */}
          <div
            ref={timeListRef}
            className="overflow-y-auto max-h-[260px] pt-[10px] px-[8px] pb-[10px]"
          >
            <div className="text-[10px] font-extrabold tracking-[1.5px] text-mute dark:text-muteDk pt-[2px] px-[6px] pb-[6px]">
              TIME
            </div>
            {TIMES.map(([h, m]) => {
              const label = slotLabel(h, m);
              const isSelected =
                parsed !== null &&
                parsed.getHours() === h &&
                parsed.getMinutes() === m;
              return (
                <button
                  key={`${h}:${m}`}
                  type="button"
                  onClick={() => pickTime(h, m)}
                  aria-pressed={isSelected}
                  className={clsx(
                    "block w-full px-[10px] py-[6px] mb-[2px] rounded-[6px] border-none text-[12px] font-bold text-left cursor-pointer transition-colors",
                    isSelected
                      ? "bg-leaf shadow-chunky-sm dark:shadow-[2px_2px_0_#000] text-ink"
                      : "bg-transparent text-ink dark:text-paper hover:bg-paper2 dark:hover:bg-white/[0.06]",
                  )}
                >
                  {label}
                </button>
              );
            })}
          </div>
        </div>
      )}
    </div>
  );
}
