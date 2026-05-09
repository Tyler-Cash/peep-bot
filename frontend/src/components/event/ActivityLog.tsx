"use client";

import { useMemo } from "react";
import clsx from "@/lib/clsx";
import { Avatar } from "@/components/ui/Avatar";
import { stringToColor } from "@/lib/format";

export type ActivityKind = "host" | "going" | "maybe" | "declined";

export type ActivityEvent = {
  id: string;
  who: string;
  avatarUrl: string | null;
  hue?: string;
  kind: ActivityKind;
  at: string;
  note?: string;
};

export type ActivityLogProps = {
  events: ActivityEvent[];
  channelName: string;
};

export function hoursAgo(iso: string, now: Date = new Date()): number {
  const then = new Date(iso).getTime();
  return Math.max(0, Math.floor((now.getTime() - then) / (1000 * 60 * 60)));
}

export function relTime(h: number): string {
  if (h <= 0) return "just now";
  if (h < 24) return `${h}h ago`;
  return `${Math.floor(h / 24)}d ago`;
}

export function joinNames(names: string[]): string {
  if (names.length === 1) return names[0];
  if (names.length === 2) return `${names[0]} & ${names[1]}`;
  if (names.length === 3) return `${names[0]}, ${names[1]} & ${names[2]}`;
  return `${names[0]}, ${names[1]} & ${names.length - 2} others`;
}

const VERB_SINGULAR: Record<Exclude<ActivityKind, "host">, string> = {
  going: "is in",
  maybe: "said maybe",
  declined: "is out",
};

const VERB_PLURAL: Record<Exclude<ActivityKind, "host">, string> = {
  going: "are in",
  maybe: "said maybe",
  declined: "are out",
};

const DOT_CLASS: Record<ActivityKind, string> = {
  host: "bg-ink",
  going: "bg-leaf",
  maybe: "bg-cat-trivia-dot",
  declined: "bg-[#DC2626]",
};

type ActivityGroup = {
  kind: ActivityKind;
  hoursAgo: number;
  source: ActivityEvent;
  note?: string;
  people: { who: string; avatarUrl: string | null; hue?: string }[];
};

export function groupEvents(
  events: ActivityEvent[],
  now: Date = new Date(),
): ActivityGroup[] {
  const sorted = [...events].sort(
    (a, b) => new Date(b.at).getTime() - new Date(a.at).getTime(),
  );
  const out: ActivityGroup[] = [];
  // Bucket key is the displayed relative time ("3h ago", "10d ago", etc). All same-kind
  // events that share a bucket fold into the most-recent group for that bucket — even if
  // a different-kind event happened between them — so a busy day reads as "going: A, B, C
  // — 10d ago" rather than fragmenting into one row per RSVP.
  const bucketKindToGroup = new Map<string, ActivityGroup>();
  for (const e of sorted) {
    const h = hoursAgo(e.at, now);
    if (e.kind === "host") {
      out.push({
        kind: e.kind,
        hoursAgo: h,
        source: e,
        note: e.note,
        people: [{ who: e.who, avatarUrl: e.avatarUrl, hue: e.hue }],
      });
      continue;
    }
    const key = `${relTime(h)}:${e.kind}`;
    const existing = bucketKindToGroup.get(key);
    if (existing) {
      existing.people.push({ who: e.who, avatarUrl: e.avatarUrl, hue: e.hue });
    } else {
      const group: ActivityGroup = {
        kind: e.kind,
        hoursAgo: h,
        source: e,
        note: e.note,
        people: [{ who: e.who, avatarUrl: e.avatarUrl, hue: e.hue }],
      };
      out.push(group);
      bucketKindToGroup.set(key, group);
    }
  }
  return out;
}

function ActivityRow({
  group,
  channelName,
}: {
  group: ActivityGroup;
  channelName: string;
}) {
  const isHost = group.kind === "host";
  const grouped = group.people.length > 1;
  const verb = isHost
    ? `posted in #${channelName}`
    : grouped
      ? VERB_PLURAL[group.kind]
      : VERB_SINGULAR[group.kind];
  const names = joinNames(group.people.map((p) => p.who));
  const visible = group.people.slice(0, 3);

  return (
    <li className="relative flex items-center gap-2.5 py-[7px] pl-8 pr-1">
      <span
        aria-hidden
        className={clsx(
          "absolute left-[7px] top-1/2 -mt-[7px] h-3.5 w-3.5 rounded-full border-[1.5px] border-ink",
          DOT_CLASS[group.kind],
        )}
      />
      <span className="inline-flex shrink-0">
        {visible.map((p, i) => (
          <span
            key={`${p.who}-${i}`}
            style={{ marginLeft: i === 0 ? 0 : -10 }}
          >
            <Avatar
              who={{
                name: p.who,
                avatarUrl: p.avatarUrl,
                hue: p.hue ?? stringToColor(p.who),
              }}
              size={26}
            />
          </span>
        ))}
      </span>
      <span className="min-w-0 flex-1 text-[13.5px] leading-snug">
        <span className="font-extrabold text-ink">{names}</span>{" "}
        <span className="text-ink2">{verb}</span>
      </span>
      <time
        dateTime={group.source.at}
        className="shrink-0 text-[11px] font-bold uppercase tracking-[0.02em] text-mute tabular-nums"
      >
        {relTime(group.hoursAgo)}
      </time>
    </li>
  );
}

export function ActivityLog({ events, channelName }: ActivityLogProps) {
  const groups = useMemo(() => groupEvents(events), [events]);
  const onlyHost = events.length === 1 && events[0].kind === "host";

  return (
    <section
      role="region"
      aria-label="rsvp activity"
      className="rounded-card border-[1.5px] border-ink bg-white px-3.5 pt-3.5 pb-1.5 shadow-rest flex flex-col gap-2"
    >
      <header className="flex items-baseline justify-between">
        <span className="text-[14px] font-extrabold uppercase tracking-[0.16em] text-mute">
          activity
        </span>
        <span className="text-[11px] font-bold uppercase tracking-[0.04em] text-mute tabular-nums">
          {events.length} event{events.length === 1 ? "" : "s"} · newest first
        </span>
      </header>

      {onlyHost && (
        <p className="my-1 text-[13px] text-mute">
          nothing yet — reactions will show up here as they come in.
        </p>
      )}

      <ol
        tabIndex={0}
        aria-label="rsvp activity, scrollable"
        className="relative m-0 max-h-60 list-none overflow-y-auto p-0"
      >
        <span
          aria-hidden
          className="pointer-events-none absolute left-[13px] top-2 bottom-2 w-[1.5px] bg-ink/[0.18]"
        />
        {groups.map((g, i) => (
          <ActivityRow
            key={`${g.source.id}:${i}`}
            group={g}
            channelName={channelName}
          />
        ))}
      </ol>

      {events.length > 4 && (
        <div className="text-center text-[11px] font-bold uppercase tracking-[0.04em] text-mute">
          scroll for more
        </div>
      )}
    </section>
  );
}

export default ActivityLog;
