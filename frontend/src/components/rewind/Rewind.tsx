"use client";

import { useMemo, useState } from "react";
import { Peepo } from "@/components/Peepo";
import { Slab } from "@/components/ui/Slab";
import { Chunky } from "@/components/ui/Chunky";
import { Avatar } from "@/components/ui/Avatar";
import { categoryMeta } from "@/lib/categories";
import { dateStamp } from "@/lib/format";
import { useRewind, useRewindYears } from "@/lib/hooks";
import type { AttendeeStatDto, EventCategoryDto } from "@/lib/types";

const DAY_ORDER = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"];

export function Rewind() {
  const currentYear = useMemo(() => new Date().getFullYear(), []);
  const [year, setYear] = useState(currentYear);
  const [scope, setScope] = useState<"guild" | "me">("guild");
  const { data: years } = useRewindYears();
  const { data } = useRewind(year, scope);

  const availableYears = years ?? [currentYear];

  return (
    <div className="mx-auto max-w-[1100px] px-5 py-6 flex flex-col gap-6">
      {/* hero */}
      <div className="relative overflow-hidden rounded-[18px] border-[1.5px] border-ink bg-ink text-paper p-7 shadow-chunky-lg">
        <div className="absolute right-[-30px] bottom-[-40px] opacity-90">
          <Peepo size={260} hue="#7BC24F" />
        </div>
        <span className="text-[11px] font-extrabold tracking-[0.2em] text-muteDk uppercase">
          PEEPBOT REWIND
        </span>
        <h1 className="mt-2 text-[72px] font-extrabold tracking-[-0.04em] leading-none">
          {year}
        </h1>
        <p className="mt-2 text-[15px] text-paper3 max-w-[480px]">
          {scope === "guild"
            ? "what happened this year in our corner of discord."
            : "your year in events."}
        </p>
        <div className="mt-5 flex items-center gap-3 flex-wrap">
          {/* year selector */}
          <div className="flex items-center gap-1">
            {availableYears.map((y) => (
              <button
                key={y}
                onClick={() => setYear(y)}
                className={
                  y === year
                    ? "px-3 py-1 rounded-full bg-paper text-ink text-[13px] font-extrabold border-[1.5px] border-paper"
                    : "px-3 py-1 rounded-full text-paper3 text-[13px] font-extrabold border-[1.5px] border-paper/30 hover:border-paper/70 transition-colors"
                }
              >
                {y}
              </button>
            ))}
          </div>
          {/* scope toggle */}
          <div className="flex items-center gap-1 ml-auto">
            <Chunky
              variant={scope === "guild" ? "leaf" : "paper"}
              size="sm"
              onClick={() => setScope("guild")}
            >
              server
            </Chunky>
            <Chunky
              variant={scope === "me" ? "leaf" : "paper"}
              size="sm"
              onClick={() => setScope("me")}
            >
              just me
            </Chunky>
          </div>
        </div>
      </div>

      {!data && (
        <div className="text-mute py-4">loading rewind…</div>
      )}

      {data && (
        <>
          {/* top moment */}
          {(data.firstEvent || data.lastEvent) && (
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
              {data.firstEvent && (
                <Slab className="p-5">
                  <span className="text-[11px] font-extrabold tracking-[0.18em] text-mute uppercase">
                    first event
                  </span>
                  <EventHighlight id={data.firstEvent.id} name={data.firstEvent.name} dateTime={data.firstEvent.dateTime} />
                </Slab>
              )}
              {data.lastEvent && (
                <Slab className="p-5">
                  <span className="text-[11px] font-extrabold tracking-[0.18em] text-mute uppercase">
                    last event
                  </span>
                  <EventHighlight id={data.lastEvent.id} name={data.lastEvent.name} dateTime={data.lastEvent.dateTime} />
                </Slab>
              )}
            </div>
          )}

          {/* stats */}
          <section className="grid grid-cols-2 lg:grid-cols-4 gap-4">
            <StatCard label="events" value={data.totalEvents} />
            <StatCard label="total rsvps" value={data.totalRsvps} />
            <StatCard label="unique attendees" value={data.totalUniqueAttendees} />
            <StatCard label="avg group size" value={data.averageGroupSize.toFixed(1)} />
          </section>
          {data.totalPlusOneGuests > 0 && (
            <div className="flex items-center gap-2 text-[13px] text-mute font-semibold -mt-3">
              <span>+{data.totalPlusOneGuests} plus-one guests across all events</span>
            </div>
          )}

          {/* top categories */}
          {data.topCategories.length > 0 && (
            <Slab className="p-5">
              <span className="text-[11px] font-extrabold tracking-[0.18em] text-mute uppercase">
                top categories
              </span>
              <div className="mt-3 flex flex-wrap gap-3">
                {data.topCategories.map((c) => (
                  <CategoryChip key={c.name} cat={c} />
                ))}
              </div>
            </Slab>
          )}

          {/* top attendees */}
          {data.topAttendees.length > 0 && (
            <Slab className="p-5">
              <span className="text-[11px] font-extrabold tracking-[0.18em] text-mute uppercase">
                most events attended
              </span>
              <div className="mt-3 flex flex-wrap gap-3">
                {data.topAttendees.map((m) => (
                  <AttendeeChip key={m.displayName} stat={m} />
                ))}
              </div>
            </Slab>
          )}

          {/* top organizers */}
          {data.topOrganizers.length > 0 && (
            <Slab className="p-5">
              <span className="text-[11px] font-extrabold tracking-[0.18em] text-mute uppercase">
                top organisers
              </span>
              <div className="mt-3 flex flex-wrap gap-3">
                {data.topOrganizers.map((m) => (
                  <AttendeeChip key={m.displayName} stat={m} />
                ))}
              </div>
            </Slab>
          )}

          {/* top social pairs */}
          {data.topSocialPairs.length > 0 && (
            <Slab className="p-5">
              <span className="text-[11px] font-extrabold tracking-[0.18em] text-mute uppercase">
                always together
              </span>
              <ul className="mt-3 flex flex-col gap-2">
                {data.topSocialPairs.map((pair, i) => (
                  <li key={i} className="flex items-center gap-2 text-[14px]">
                    <span className="font-extrabold">{pair.user1}</span>
                    <span className="text-mute">+</span>
                    <span className="font-extrabold">{pair.user2}</span>
                    <span className="ml-auto text-mute font-semibold">{pair.sharedEvents} events</span>
                  </li>
                ))}
              </ul>
            </Slab>
          )}

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            {/* events by month */}
            {Object.keys(data.eventsByMonth).length > 0 && (
              <Slab className="p-5">
                <span className="text-[11px] font-extrabold tracking-[0.18em] text-mute uppercase">
                  activity by month
                </span>
                <MonthChart data={data.eventsByMonth} />
              </Slab>
            )}

            {/* events by day of week */}
            {Object.keys(data.eventsByDayOfWeek).length > 0 && (
              <Slab className="p-5">
                <span className="text-[11px] font-extrabold tracking-[0.18em] text-mute uppercase">
                  best day of the week
                </span>
                <DayChart data={data.eventsByDayOfWeek} />
              </Slab>
            )}
          </div>
        </>
      )}
    </div>
  );
}

function StatCard({ label, value }: { label: string; value: number | string }) {
  return (
    <Slab className="p-4">
      <span className="text-[10.5px] font-extrabold tracking-[0.18em] text-mute uppercase">
        {label}
      </span>
      <p className="mt-1 text-[32px] font-extrabold tracking-[-0.02em] leading-none">{value}</p>
    </Slab>
  );
}

function AttendeeChip({ stat }: { stat: AttendeeStatDto }) {
  return (
    <div className="flex items-center gap-2 rounded-full border-[1.5px] border-ink bg-paper pl-0.5 pr-3 py-0.5">
      <Avatar
        who={{ name: stat.displayName, hue: "#7BC24F", avatarUrl: stat.avatarUrl }}
        size={24}
      />
      <span className="text-[13px] font-extrabold">{stat.displayName}</span>
      <span className="text-[12px] text-mute font-semibold">· {stat.eventCount}</span>
    </div>
  );
}

function CategoryChip({ cat }: { cat: EventCategoryDto }) {
  const m = categoryMeta(cat.name);
  return (
    <div
      className="flex items-center gap-2 rounded-full border-[1.5px] border-ink px-3 py-1"
      style={{ background: m.bg, color: m.ink }}
    >
      <span aria-hidden>{m.emoji}</span>
      <span className="text-[13px] font-extrabold capitalize">{cat.name}</span>
      <span className="text-[12px] font-semibold opacity-70">· {cat.eventCount}</span>
    </div>
  );
}

function EventHighlight({ id, name, dateTime }: { id: string; name: string; dateTime: string }) {
  const s = dateStamp(dateTime);
  return (
    <div className="mt-2">
      <p className="text-[11px] font-semibold text-mute">{s.month} {s.day}</p>
      <a href={`/events/${id}`} className="text-[18px] font-extrabold tracking-[-0.02em] hover:underline">
        {name}
      </a>
    </div>
  );
}

function MonthChart({ data }: { data: Record<string, number> }) {
  const entries = Object.entries(data).sort(([a], [b]) => a.localeCompare(b));
  const max = Math.max(...entries.map(([, v]) => v), 1);
  return (
    <div className="mt-3 flex items-end gap-1.5 h-[60px]">
      {entries.map(([month, count]) => {
        const label = month.slice(5);
        const heightPct = Math.max((count / max) * 100, 8);
        return (
          <div key={month} className="flex flex-col items-center gap-1 flex-1">
            <div
              className="w-full rounded-t-[4px] bg-leaf border-[1px] border-ink"
              style={{ height: `${heightPct}%` }}
              title={`${month}: ${count}`}
            />
            <span className="text-[9px] font-bold text-mute">{label}</span>
          </div>
        );
      })}
    </div>
  );
}

function DayChart({ data }: { data: Record<string, number> }) {
  const entries = DAY_ORDER.map((day) => [day, data[day] ?? 0] as [string, number]);
  const max = Math.max(...entries.map(([, v]) => v), 1);
  return (
    <div className="mt-3 flex items-end gap-1.5 h-[60px]">
      {entries.map(([day, count]) => {
        const heightPct = Math.max((count / max) * 100, 8);
        return (
          <div key={day} className="flex flex-col items-center gap-1 flex-1">
            <div
              className="w-full rounded-t-[4px] bg-leaf border-[1px] border-ink"
              style={{ height: `${heightPct}%` }}
              title={`${day}: ${count}`}
            />
            <span className="text-[9px] font-bold text-mute">{day.slice(0, 2)}</span>
          </div>
        );
      })}
    </div>
  );
}
