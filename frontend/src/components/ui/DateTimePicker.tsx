"use client";

import { useEffect, useRef, useState } from "react";
import clsx from "@/lib/clsx";

type Value = string | null; // "YYYY-MM-DDTHH:mm" — same shape datetime-local emits

type Props = {
  value: Value;
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
  "w-[26px] h-[26px] bg-paper2 dark:bg-white/[0.06] border border-line dark:border-lineDk rounded-chip text-[14px] font-extrabold text-ink dark:text-paper inline-flex items-center justify-center cursor-pointer";

// Shared trigger chrome — matches components-event-composer.html: 48px high,
// white bg, 10px radius, 3px ink shadow, trail chevron in a 36×36 paper chip.
// When open, the popover joins seamlessly: square bottom corners, no bottom
// border, and the trigger keeps only a right-side shadow so the drop-shadow
// stays continuous across the joined surface.
const triggerBase =
  "w-full h-12 border-[1.5px] border-ink bg-white pl-[14px] pr-1 text-[16px] flex items-center gap-2 text-left cursor-pointer focus-visible:outline-none";
const triggerOpen = "rounded-t-chip rounded-b-none";
const triggerClosed = "rounded-chip shadow-rest";
const triggerOpenStyle: React.CSSProperties = {
  boxShadow: "3px 0 0 #0E100D",
  borderBottomStyle: "dashed",
  borderBottomWidth: "1px",
  borderBottomColor: "rgba(14,16,13,0.2)",
};

const trailChipCls =
  "ml-auto inline-flex items-center justify-center w-9 h-9 rounded-chip border-[1.5px] border-ink bg-paper text-[18px] font-extrabold leading-none shadow-press";

function usePopover() {
  const [open, setOpen] = useState(false);
  const wrapRef = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    const onDoc = (e: MouseEvent) => {
      if (wrapRef.current && !wrapRef.current.contains(e.target as Node)) setOpen(false);
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
  return { open, setOpen, wrapRef };
}

// =============================================================================
// DatePicker — calendar-only popover. If `value` is null when a date is picked,
// time defaults to 18:30 to keep the combined ISO string valid.
// =============================================================================
export function DatePicker({ value, onChange }: Props) {
  const parsed = value ? new Date(value) : null;
  const today = new Date();
  const startOfToday = new Date(today.getFullYear(), today.getMonth(), today.getDate());

  const { open, setOpen, wrapRef } = usePopover();
  const [viewYear, setViewYear] = useState(parsed?.getFullYear() ?? today.getFullYear());
  const [viewMonth, setViewMonth] = useState(parsed?.getMonth() ?? today.getMonth());

  useEffect(() => {
    if (value) {
      const d = new Date(value);
      setViewYear(d.getFullYear());
      setViewMonth(d.getMonth());
    }
  }, [value]);

  const label = parsed
    ? parsed
        .toLocaleString("en", { weekday: "short", day: "numeric", month: "short" })
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
    const next = new Date(viewYear, viewMonth, day, cur.getHours(), cur.getMinutes());
    onChange(toISO(next));
    setOpen(false);
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
        onClick={() => setOpen((o) => !o)}
        className={clsx(triggerBase, open ? triggerOpen : triggerClosed)}
        style={open ? triggerOpenStyle : undefined}
      >
        <span className={clsx(label ? "font-semibold text-ink" : "font-medium text-mute")}>
          {label ?? "pick a date"}
        </span>
        <span className={trailChipCls} aria-hidden>
          ▾
        </span>
      </button>

      {open && (
        <div
          role="dialog"
          aria-label="pick date"
          className="absolute top-full left-0 right-0 z-10 bg-white dark:bg-ink3 border-[1.5px] border-t-0 border-ink dark:border-[#3A3F36] rounded-b-chip shadow-[3px_0_0_#0E100D,0_3px_0_#0E100D] dark:shadow-[4px_4px_0_#000] overflow-hidden"
        >
          <div className="pt-[14px] px-[14px] pb-[12px]">
            <div className="flex items-center justify-between mb-[10px]">
              <button type="button" onClick={prevMonth} aria-label="previous month" className={navBtnCls}>
                ‹
              </button>
              <span className="text-[13px] font-extrabold tracking-[-0.2px] text-ink dark:text-paper">
                {monthLabel}
              </span>
              <button type="button" onClick={nextMonth} aria-label="next month" className={navBtnCls}>
                ›
              </button>
            </div>
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
                    aria-label={cellDate.toLocaleDateString("en", { dateStyle: "full" })}
                    aria-pressed={isSelected}
                    className={clsx(
                      "aspect-square rounded-chip text-[12px] font-bold p-0 border-[1.5px] transition-colors",
                      isSelected
                        ? "bg-leaf border-ink shadow-rest -translate-x-px -translate-y-px text-ink cursor-pointer"
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
        </div>
      )}
    </div>
  );
}

// =============================================================================
// TimePicker — half-hourly slots from 8:00am to 10:30pm. If `value` is null
// when a time is picked, date defaults to today.
// =============================================================================
export function TimePicker({ value, onChange }: Props) {
  const parsed = value ? new Date(value) : null;
  const { open, setOpen, wrapRef } = usePopover();
  const listRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (!open || !listRef.current) return;
    const selected = listRef.current.querySelector('[aria-pressed="true"]');
    selected?.scrollIntoView({ block: "center" });
  }, [open]);

  const label = parsed
    ? slotLabel(parsed.getHours(), parsed.getMinutes()).replace(/^0/, "")
    : null;

  const pickTime = (h: number, m: number) => {
    const base = parsed ?? new Date();
    const next = new Date(
      base.getFullYear(),
      base.getMonth(),
      base.getDate(),
      h,
      m,
    );
    onChange(toISO(next));
    setOpen(false);
  };

  return (
    <div ref={wrapRef} className="relative w-full">
      <button
        type="button"
        aria-haspopup="dialog"
        aria-expanded={open}
        onClick={() => setOpen((o) => !o)}
        className={clsx(triggerBase, open ? triggerOpen : triggerClosed)}
        style={open ? triggerOpenStyle : undefined}
      >
        <span className={clsx(label ? "font-semibold text-ink" : "font-medium text-mute")}>
          {label ?? "pick a time"}
        </span>
        <span className={trailChipCls} aria-hidden>
          ▾
        </span>
      </button>

      {open && (
        <div
          ref={listRef}
          role="dialog"
          aria-label="pick time"
          className="absolute top-full left-0 right-0 z-10 max-h-[260px] overflow-y-auto bg-white dark:bg-ink3 border-[1.5px] border-t-0 border-ink dark:border-[#3A3F36] rounded-b-chip shadow-[3px_0_0_#0E100D,0_3px_0_#0E100D] dark:shadow-[4px_4px_0_#000] pt-[10px] px-[8px] pb-[10px]"
        >
          <div className="text-[10px] font-extrabold tracking-[1.5px] text-mute dark:text-muteDk pt-[2px] px-[6px] pb-[6px]">
            TIME
          </div>
          {TIMES.map(([h, m]) => {
            const slot = slotLabel(h, m);
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
                  "block w-full px-[10px] py-[6px] mb-[2px] rounded-chip border-none text-[12px] font-bold text-left cursor-pointer transition-colors",
                  isSelected
                    ? "bg-leaf shadow-rest dark:shadow-[2px_2px_0_#000] text-ink"
                    : "bg-transparent text-ink dark:text-paper hover:bg-paper2 dark:hover:bg-white/[0.06]",
                )}
              >
                {slot}
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}

// Combined picker kept as a thin wrapper for surfaces that haven't moved to the
// split layout yet.
export function DateTimePicker({ value, onChange }: Props) {
  return (
    <div className="grid grid-cols-2 gap-3">
      <DatePicker value={value} onChange={onChange} />
      <TimePicker value={value} onChange={onChange} />
    </div>
  );
}
