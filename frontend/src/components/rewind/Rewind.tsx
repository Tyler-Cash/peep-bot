"use client";

import { useMemo } from "react";
import { Peepo } from "@/components/Peepo";
import { Slab } from "@/components/ui/Slab";
import { CatTag } from "@/components/ui/CatTag";
import { Avatar } from "@/components/ui/Avatar";
import { categoryMeta } from "@/lib/categories";
import { dateStamp, timeLabel } from "@/lib/format";
import { useRewind } from "@/lib/hooks";

export function Rewind() {
  const year = useMemo(() => new Date().getFullYear(), []);
  const { data } = useRewind(year);

  if (!data) {
    return <div className="mx-auto max-w-[1100px] p-8 text-mute">loading rewind…</div>;
  }

  const topCat = data.topMoment ? categoryMeta(data.topMoment.category) : null;

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
      {data.topMoment && topCat && (
        <Slab className="p-6" as="section">
          <span className="text-[11px] font-extrabold tracking-[0.18em] text-mute uppercase">
            top moment
          </span>
          <div
            className="mt-3 rounded-[14px] border-[1.5px] border-ink p-5 flex items-center gap-4 shadow-chunky-sm"
            style={{ background: topCat.bg, color: topCat.ink }}
          >
            {topCat.emoji ? (
              <span className="text-[72px]" aria-hidden>
                {topCat.emoji}
              </span>
            ) : (
              <span
                aria-hidden
                className="h-10 w-10 rounded-full border-[1.5px] border-ink"
                style={{ background: topCat.dot }}
              />
            )}
            <div>
              <CatTag category={data.topMoment.category} />
              <h2 className="mt-1.5 text-[32px] font-extrabold tracking-[-0.03em] leading-[1.05]">
                {data.topMoment.name}
              </h2>
            </div>
          </div>
        </Slab>
      )}

      {/* stats */}
      <section className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        <StatCard label="events hosted" value={data.eventsHosted} />
        <StatCard label="total rsvps" value={data.totalRsvps} />
        <StatCard label="new members" value={data.newMembers} />
        <StatCard
          label="most active"
          value={data.mostActiveMember ? `${data.mostActiveMember.name} (${data.mostActiveMember.count})` : "—"}
        />
      </section>

      {/* streak */}
      <Slab className="p-5">
        <span className="text-[11px] font-extrabold tracking-[0.18em] text-mute uppercase">
          attendance streak
        </span>
        <div className="mt-3 flex flex-wrap gap-3">
          {(data.attendanceStreak ?? []).map((m, i) => (
            <div
              key={i}
              className="flex items-center gap-2 rounded-full border-[1.5px] border-ink bg-paper pl-0.5 pr-3 py-0.5"
            >
              <Avatar who={{ name: m.name, hue: m.hue, avatarUrl: m.avatarUrl }} size={24} />
              <span className="text-[13px] font-extrabold">{m.name}</span>
              <span className="text-[12px] text-mute font-semibold">· {m.count}</span>
            </div>
          ))}
        </div>
      </Slab>

      {/* upcoming */}
      {(data.upcomingPreview ?? []).length > 0 && (
        <Slab className="p-5">
          <span className="text-[11px] font-extrabold tracking-[0.18em] text-mute uppercase">
            what&apos;s next
          </span>
          <ul className="mt-3 flex flex-col gap-2">
            {data.upcomingPreview.map((e) => {
              const s = dateStamp(e.dateTime);
              const c = categoryMeta(e.category);
              return (
                <li
                  key={e.id}
                  className="flex items-center gap-3 rounded-[10px] border-[1.5px] border-ink px-3 py-2"
                  style={{ background: c.bg, color: c.ink }}
                >
                  <span className="text-[11px] font-extrabold tracking-[0.14em] w-14 text-center">
                    {s.month} {s.day}
                  </span>
                  <span className="text-[15px] font-extrabold flex-1">{e.name}</span>
                  <span className="text-[12px] font-semibold">
                    {timeLabel(e.dateTime)} · {e.location}
                  </span>
                </li>
              );
            })}
          </ul>
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
