"use client";

import { useMemo } from "react";
import { Peepo } from "@/components/Peepo";
import { Slab } from "@/components/ui/Slab";
import { Avatar } from "@/components/ui/Avatar";
import { categoryMeta } from "@/lib/categories";
import { dateStamp } from "@/lib/format";
import { useRewind } from "@/lib/hooks";
import type { AttendeeStatDto, EventCategoryDto } from "@/lib/types";

export function Rewind() {
  const year = useMemo(() => new Date().getFullYear(), []);
  const { data } = useRewind(year);

  if (!data) {
    return <div className="mx-auto max-w-[1100px] p-8 text-mute">loading rewind…</div>;
  }

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
          what happened this year in our corner of discord.
        </p>
      </div>

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

      {/* events by month */}
      {Object.keys(data.eventsByMonth).length > 0 && (
        <Slab className="p-5">
          <span className="text-[11px] font-extrabold tracking-[0.18em] text-mute uppercase">
            activity by month
          </span>
          <MonthChart data={data.eventsByMonth} />
        </Slab>
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
        const label = month.slice(5); // "MM" portion
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
