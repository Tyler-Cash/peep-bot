"use client";

import Link from "next/link";
import { Chunky } from "@/components/ui/Chunky";
import { DayMarker } from "@/components/ui/DayMarker";
import { PeepoSleep } from "@/components/Peepo";
import { monthKey, monthLabel } from "@/lib/format";
import { useEvents } from "@/lib/hooks";
import { FeedCard } from "./FeedCard";
import {EventDto} from "@/lib/types";

export function EventsFeed() {
  const { data, error, isLoading } = useEvents();
  const events = data?.content ?? [];
  const count = events.length;

  return (
    <div className="mx-auto max-w-[980px] px-5 py-6">
      <header className="flex items-end justify-between mb-5">
        <h1 className="text-[54px] sm:text-[64px] font-extrabold tracking-[-0.04em] leading-[0.95]">
          what&apos;s happening
        </h1>
        <span
          className="inline-flex items-center gap-1.5 rounded-full border-[1.5px] border-ink bg-leaf px-4 py-1.5 text-[15px] font-extrabold shadow-chunky-sm"
          style={{ transform: "rotate(-2deg)" }}
        >
          {count} upcoming ✨
        </span>
      </header>

      <Link
        href="/events/new"
        className="flex items-center gap-3 rounded-full border-[1.5px] border-ink bg-white pl-5 pr-4 py-3 shadow-chunky-sm hover:bg-paper2 transition-colors"
      >
        <span className="text-[18px] font-semibold text-mute flex-1">
          ＋ post an event to #outings…
        </span>
        <Chunky variant="leaf" size="sm">
          new event
        </Chunky>
      </Link>

      {isLoading && (
        <p className="mt-8 text-center text-mute text-[14px]">loading plans…</p>
      )}
      {error && !events.length && (
        <div className="mt-10 flex flex-col items-center gap-3 text-center">
          <PeepoSleep size={90} />
          <p className="text-mute text-[14px]">
            can&apos;t reach the backend. showing whatever we had cached.
          </p>
        </div>
      )}

      <section className="mt-4">
        {renderWithMonthMarkers(events)}
      </section>
    </div>
  );
}

function renderWithMonthMarkers(events: EventDto[]) {
  const out: React.ReactNode[] = [];
  const groups: { key: string; items: EventDto[] }[] = [];

  for (const event of events) {
    const key = monthKey(event.dateTime);
    const tail = groups[groups.length - 1];
    if (!tail || tail.key !== key) {
      groups.push({ key, items: [event] });
    } else {
      tail.items.push(event);
    }
  }

  for (const { key, items } of groups) {
    out.push(<DayMarker key={`m-${key}`} label={monthLabel(items[0].dateTime)} />);
    items.forEach((event, i) => {
      out.push(<FeedCard key={event.id} event={event} last={i === items.length - 1} />);
    });
  }
  return out;
}
