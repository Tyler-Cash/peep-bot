"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import Link from "next/link";
import { Chunky } from "@/components/ui/Chunky";
import { Slab } from "@/components/ui/Slab";
import { DateTimePicker } from "@/components/ui/DateTimePicker";
import { LocationAutocomplete } from "@/components/ui/LocationAutocomplete";
import { CatTag } from "@/components/ui/CatTag";
import { CountdownChip } from "@/components/ui/CountdownChip";
import { categoryMeta } from "@/lib/categories";
import { dateStamp, dateToLocalInput, timeLabel } from "@/lib/format";
import {
  updateEvent,
  useActiveGuild,
  useEvent,
  useRecentLocations,
} from "@/lib/hooks";

export function EditEventForm({ id }: { id: string }) {
  const router = useRouter();
  const guild = useActiveGuild();
  const { data, isLoading } = useEvent(id);
  const recentVenues = useRecentLocations();

  const [name, setName] = useState("");
  const [date, setDate] = useState("");
  const [location, setLocation] = useState("");
  const [locationPlaceId, setLocationPlaceId] = useState("");
  const [info, setInfo] = useState("");
  const [capacity, setCapacity] = useState(0);
  const [submitting, setSubmitting] = useState(false);
  const [initialized, setInitialized] = useState(false);

  const locationBias =
    guild?.primaryLocationLat != null && guild?.primaryLocationLng != null
      ? { lat: guild.primaryLocationLat, lng: guild.primaryLocationLng }
      : undefined;

  // Populate fields once the event data arrives
  useEffect(() => {
    if (data && !initialized) {
      setName(data.name);
      setDate(dateToLocalInput(new Date(data.dateTime)));
      setLocation(data.location ?? "");
      setLocationPlaceId(data.locationPlaceId ?? "");
      setInfo(data.description ?? "");
      setCapacity(data.capacity ?? 0);
      setInitialized(true);
    }
  }, [data, initialized]);

  if (isLoading || !data) {
    return <div className="mx-auto max-w-[820px] p-8 text-mute">loading…</div>;
  }

  const cat = categoryMeta(data.category);
  const stamp = dateStamp(new Date(date || data.dateTime).toISOString());

  const onSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!guild) return;
    setSubmitting(true);
    try {
      await updateEvent(guild.id, data.id, {
        name,
        description: info,
        location: location.trim() || "",
        ...(locationPlaceId ? { locationPlaceId } : {}),
        capacity,
        dateTime: new Date(date).toISOString(),
      });
      router.push(`/events/${id}`);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="mx-auto max-w-[820px] px-5 py-6 min-h-screen flex flex-col">
      <div className="flex items-center justify-between mb-5">
        <Link
          href={`/events/${id}`}
          className="inline-flex items-center gap-1.5 text-[18px] font-semibold text-mute hover:text-ink"
        >
          ← back to event
        </Link>
      </div>

      <header className="flex items-center gap-3 mb-5">
        <span
          className="inline-flex items-center justify-center w-10 h-10 rounded-[10px] border-[1.5px] border-ink shadow-chunky-sm"
          style={{ background: cat.bg }}
        >
          {cat.emoji ? (
            <span className="text-[18px]">{cat.emoji}</span>
          ) : (
            <span
              aria-hidden
              className="h-3 w-3 rounded-full"
              style={{ background: cat.dot }}
            />
          )}
        </span>
        <div>
          <span className="text-[13px] font-extrabold tracking-[0.18em] text-mute uppercase">
            EDIT EVENT
          </span>
          <h1 className="text-[42px] font-extrabold tracking-[-0.03em] leading-none mt-0.5">
            update the details
          </h1>
        </div>
      </header>

      {/* live preview */}
      <div
        className="relative rounded-[14px] border-[1.5px] border-ink shadow-chunky-md overflow-hidden p-4 flex items-start gap-3"
        style={{ background: cat.bg, color: cat.ink }}
      >
        {cat.emoji && (
          <span
            className="absolute text-[160px] leading-none opacity-[0.16] select-none pointer-events-none"
            style={{ right: -12, bottom: -40, transform: "rotate(-12deg)" }}
            aria-hidden
          >
            {cat.emoji}
          </span>
        )}
        <div className="flex flex-col items-center justify-center rounded-[12px] bg-white/90 border-[1.5px] border-ink px-3 py-2 w-[86px] shrink-0 shadow-chunky-sm">
          <span className="text-[13px] font-extrabold tracking-[0.14em]">
            {stamp.month}
          </span>
          <span className="text-[36px] font-extrabold leading-none tabular-nums">
            {stamp.day}
          </span>
          <span className="text-[13px] font-extrabold tracking-[0.14em] uppercase">
            {stamp.weekday}
          </span>
        </div>
        <div className="relative flex-1 min-w-0">
          <div className="flex items-center gap-2">
            <CatTag category={data.category} state={data.state} />
            <CountdownChip
              iso={new Date(date || data.dateTime).toISOString()}
            />
          </div>
          <h2 className="mt-1.5 text-[32px] sm:text-[36px] font-extrabold tracking-[-0.03em] leading-[1.05]">
            {name || "your event title"}
          </h2>
          <p className="mt-1 text-[16px] font-semibold">
            {timeLabel(new Date(date || data.dateTime).toISOString())}
            {location ? ` · 📍 ${location}` : ""}
          </p>
        </div>
      </div>

      <form onSubmit={onSubmit} className="mt-5 flex flex-col gap-4 flex-1 min-h-0">
        <Slab className="p-5 flex flex-col gap-4 flex-1 min-h-0">
          <Field label="name">
            <input
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="what's the plan?"
              required
              className={inputCls}
            />
          </Field>

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <Field label="date & time">
              <DateTimePicker value={date || null} onChange={setDate} />
            </Field>
            <Field label="capacity (0 = unlimited)">
              <input
                type="number"
                min={0}
                value={capacity}
                onChange={(e) => setCapacity(Number(e.target.value))}
                className={inputCls}
              />
            </Field>
          </div>

          <Field label="venue">
            <LocationAutocomplete
              value={location}
              onChange={(v) => { setLocation(v); setLocationPlaceId(""); }}
              onPick={(placeId) => setLocationPlaceId(placeId)}
              placeholder="where?"
              recent={recentVenues}
              locationBias={locationBias}
            />
          </Field>

          <label className="flex flex-col gap-1.5 flex-1 min-h-0">
            <span className="text-[13px] font-extrabold tracking-[0.18em] text-mute uppercase">
              info
            </span>
            <textarea
              value={info}
              onChange={(e) => setInfo(e.target.value)}
              placeholder="what do people need to know?"
              className={inputCls + " flex-1 min-h-[120px] resize-none"}
            />
          </label>
        </Slab>

        <div className="flex items-center justify-between">
          <Link
            href={`/events/${id}`}
            className="inline-flex items-center gap-1.5 text-[18px] font-semibold text-mute hover:text-ink"
          >
            cancel
          </Link>
          <Chunky type="submit" variant="leaf" size="lg" disabled={submitting}>
            {submitting ? "saving…" : "save changes"}
          </Chunky>
        </div>
      </form>
    </div>
  );
}

const inputCls =
  "w-full rounded-[10px] border-[1.5px] border-ink bg-paper2 px-3 py-2 text-[17px] font-medium shadow-chunky-sm focus:outline-none focus:shadow-chunky-md";

function Field({
  label,
  children,
}: {
  label: string;
  children: React.ReactNode;
}) {
  return (
    <label className="flex flex-col gap-1.5">
      <span className="text-[13px] font-extrabold tracking-[0.18em] text-mute uppercase">
        {label}
      </span>
      {children}
    </label>
  );
}
