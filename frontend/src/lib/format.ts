export function countdownLabel(iso: string): string {
  const ms = new Date(iso).getTime() - Date.now();
  if (ms < 0) return "past";
  const mins = Math.round(ms / 60000);
  if (mins < 60) return `in ${mins}m`;
  const hrs = Math.round(mins / 60);
  if (hrs < 24) return `in ${hrs}h`;
  const days = Math.round(hrs / 24);
  if (days < 7) return `in ${days} day${days === 1 ? "" : "s"}`;
  const wks = Math.round(days / 7);
  if (wks < 5) return `in ${wks} wk${wks === 1 ? "" : "s"}`;
  const months = Math.round(days / 30);
  return `in ${months} mo`;
}

const monthsShort = [
  "JAN",
  "FEB",
  "MAR",
  "APR",
  "MAY",
  "JUN",
  "JUL",
  "AUG",
  "SEP",
  "OCT",
  "NOV",
  "DEC",
];
const weekdays = ["sun", "mon", "tue", "wed", "thu", "fri", "sat"];

export function dateStamp(iso: string) {
  const d = new Date(iso);
  return {
    month: monthsShort[d.getMonth()],
    day: d.getDate(),
    weekday: weekdays[d.getDay()],
  };
}

export function monthKey(iso: string) {
  const d = new Date(iso);
  return `${d.getFullYear()}-${d.getMonth()}`;
}

export function monthLabel(iso: string) {
  const full = [
    "JANUARY",
    "FEBRUARY",
    "MARCH",
    "APRIL",
    "MAY",
    "JUNE",
    "JULY",
    "AUGUST",
    "SEPTEMBER",
    "OCTOBER",
    "NOVEMBER",
    "DECEMBER",
  ];
  const d = new Date(iso);
  return full[d.getMonth()];
}

export function timeLabel(iso: string) {
  const d = new Date(iso);
  const h = d.getHours();
  const m = d.getMinutes();
  const am = h < 12;
  const h12 = ((h + 11) % 12) + 1;
  return `${h12}:${m.toString().padStart(2, "0")}${am ? "am" : "pm"}`;
}

export function postedRelative(iso: string) {
  const ms = Date.now() - new Date(iso).getTime();
  const mins = Math.floor(ms / 60000);
  if (mins < 1) return "just now";
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.floor(mins / 60);
  if (hrs < 24) return `${hrs}h ago`;
  const days = Math.floor(hrs / 24);
  return `${days}d ago`;
}

export function dateToLocalInput(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export function initials(name: string) {
  const parts = name.split(/\s+/).filter((p) => /^[a-zA-Z]/.test(p));
  const first = parts[0]?.[0] ?? "";
  const last = parts.length > 1 ? parts[parts.length - 1][0] : "";
  return (first + last).toUpperCase() || name.slice(0, 2).toUpperCase();
}

// Deterministic small tilt from a seed string. Same seed always produces the same angle.
// `range` is the max absolute degree value; output sits in [-range, range] but
// never lands exactly on 0 so cards never look dead-flat.
export function seededTilt(seed: string, range = 1.6): number {
  let h = 0;
  const s = String(seed);
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) | 0;
  const n = ((h % 1000) / 1000) * 2 - 1;
  const sign = n < 0 ? -1 : 1;
  return (0.3 + Math.abs(n) * (range - 0.3)) * sign;
}

export function stringToColor(str: string): string {
  let hash = 0;
  for (let i = 0; i < str.length; i++) {
    hash = str.charCodeAt(i) + ((hash << 5) - hash);
  }
  const hue = Math.abs(hash % 360);
  return `hsl(${hue}, 65%, 80%)`;
}
