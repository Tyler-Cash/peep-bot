"use client";

import { useRouter } from "next/navigation";
import clsx from "@/lib/clsx";
import { CatTag } from "@/components/ui/CatTag";
import { CountdownChip } from "@/components/ui/CountdownChip";
import { ReactionRow } from "@/components/ui/ReactionRow";
import { Avas } from "@/components/ui/Avas";
import { Avatar } from "@/components/ui/Avatar";
import { categoryMeta } from "@/lib/categories";
import { dateStamp, seededTilt, timeLabel } from "@/lib/format";
import { submitRsvp, useActiveGuild, useCurrentUser, useEvent } from "@/lib/hooks";
import type { EventDto, RsvpStatus } from "@/lib/types";

export function FeedCard({ event, last }: { event: EventDto; last?: boolean }) {
  const router = useRouter();
  const { data: detail, mutate } = useEvent(event.id);
  const { data: me } = useCurrentUser();
  const guild = useActiveGuild();
  const cat = categoryMeta(event.category);
  const stamp = dateStamp(event.dateTime);
  const tileTilt = seededTilt(`tile-${event.id}`, 1.6);
  const rsvpTilt = seededTilt(`rsvp-${event.id}`, 2);

  const ds = detail ?? {
    accepted: [],
    maybe: [],
    declined: [],
  };
  const counts = {
    going: ds.accepted.length,
    maybe: ds.maybe.length,
    declined: ds.declined.length,
  };
  const active: RsvpStatus | null =
    ds.accepted.some((a) => a.snowflake === me?.discordId)
      ? "going"
      : ds.maybe.some((a) => a.snowflake === me?.discordId)
        ? "maybe"
        : ds.declined.some((a) => a.snowflake === me?.discordId)
          ? "declined"
          : null;

  const onPick = async (status: RsvpStatus) => {
    if (!guild || !me) return;
    // optimistic
    mutate(
      (prev) => {
        if (!prev) return prev;
        const strip = (list: typeof prev.accepted) =>
          list.filter((a) => a.snowflake !== me.discordId);
        const meAttendee = {
          snowflake: me.discordId,
          name: me.username,
          instant: new Date().toISOString(),
          avatarUrl: me.avatarUrl ?? null,
          hue: "#7BC24F",
        };
        const next = {
          ...prev,
          accepted: strip(prev.accepted),
          maybe: strip(prev.maybe),
          declined: strip(prev.declined),
        };
        if (status === "going") next.accepted.push(meAttendee);
        if (status === "maybe") next.maybe.push(meAttendee);
        if (status === "declined") next.declined.push(meAttendee);
        return next;
      },
      { revalidate: false },
    );
    await submitRsvp(guild.id, event.id, status);
  };

  return (
    <article className={clsx("flex gap-3 px-3 py-3 rounded-chip transition-colors hover:bg-leaf/5", !last && "border-b border-line")}>
      <Avatar
        who={{ name: event.host, username: event.hostUsername, avatarUrl: event.hostAvatarUrl }}
        size={40}
        className="mt-0.5"
      />
      <div className="flex-1 min-w-0">
        <header className="flex items-center gap-2 text-[15px]">
          <span className="font-extrabold text-ink">{event.host}</span>
          <span className="text-mute">@{event.hostUsername ?? event.host?.toLowerCase()} · posted recently</span>
          <span className="flex-1" />
          <CountdownChip iso={event.dateTime} />
        </header>

        <div
          onClick={() => router.push(`/events/${event.id}`)}
          onMouseEnter={() => router.prefetch(`/events/${event.id}`)}
          className="block mt-2 rounded-card border-[1.5px] border-ink overflow-hidden shadow-rest bg-white cursor-pointer"
        >
          <div
            className="relative p-4 pr-[112px] border-b-[1.5px] border-ink overflow-hidden"
            style={{ background: cat.bg, color: cat.ink }}
          >
            {cat.image ? (
              // eslint-disable-next-line @next/next/no-img-element
              <img
                src={cat.image}
                alt=""
                aria-hidden
                width={140}
                height={140}
                className="absolute select-none pointer-events-none opacity-[0.22]"
                style={{ left: -22, bottom: -46, transform: "rotate(8deg)" }}
              />
            ) : cat.emoji ? (
              <span
                className="absolute text-[140px] leading-none opacity-[0.22] select-none pointer-events-none"
                style={{ left: -22, bottom: -46, transform: "rotate(8deg)" }}
                aria-hidden
              >
                {cat.emoji}
              </span>
            ) : null}
            {/* horizontal date tile, pinned top-right with deterministic tilt */}
            <div
              className="absolute top-3 right-3 z-[2] inline-flex items-stretch border-[1.5px] border-ink rounded-chip shadow-rest overflow-hidden bg-white/95"
              style={{ transform: `rotate(${tileTilt}deg)` }}
            >
              <span className="flex items-center gap-1.5 px-3 py-2">
                <span className="text-[26px] font-extrabold leading-none tracking-[-0.04em] tabular-nums">
                  {stamp.day}
                </span>
                <span className="text-[20px] font-extrabold leading-none tracking-[-0.03em] lowercase">
                  {stamp.month.toLowerCase()}
                </span>
              </span>
              <span className="flex items-center justify-center px-3 border-l-[1.5px] border-ink bg-white/55">
                <span className="text-[13px] font-extrabold tracking-[0.04em] lowercase leading-none">
                  {stamp.weekday}
                </span>
              </span>
            </div>
            {/* content — pl clears watermark so the title never collides */}
            <div className="relative pl-[80px] min-w-0">
              <CatTag category={event.category} displayState={event.displayState} />
              <h2 className="mt-1.5 text-[30px] sm:text-[34px] font-extrabold tracking-[-0.03em] leading-[1.05]">
                {event.name}
              </h2>
              <p className="mt-1 text-[16px] font-semibold opacity-95">
                {timeLabel(event.dateTime)}{event.location ? ` · 📍 ${event.location}` : ""}
              </p>
            </div>
          </div>
          <div className="bg-white p-4">
            <p className="text-[17px] text-ink2 line-clamp-2">{event.description}</p>
            <div className="mt-3 flex items-center gap-3 flex-wrap">
              <Avas people={ds.accepted} max={5} size={28} />
              <span className="text-[15px] text-mute font-semibold">
                {counts.going} going · {counts.maybe} maybe
              </span>
              <span className="flex-1" />
              <ReactionRow counts={counts} active={active} onPick={onPick} tilt={rsvpTilt} />
            </div>
          </div>
        </div>
      </div>
    </article>
  );
}
