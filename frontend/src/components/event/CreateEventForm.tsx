"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { Chunky } from "@/components/ui/Chunky";
import { Slab } from "@/components/ui/Slab";
import { DateTimePicker } from "@/components/ui/DateTimePicker";
import { LocationAutocomplete } from "@/components/ui/LocationAutocomplete";
import { Peepo } from "@/components/Peepo";
import { dateStamp, timeLabel } from "@/lib/format";
import { createEvent, useActiveGuild, useRecentLocations } from "@/lib/hooks";

export function CreateEventForm() {
  const router = useRouter();
  const guild = useActiveGuild();
  const recentVenues = useRecentLocations();
  const [name, setName] = useState("");
  const [date, setDate] = useState(() =>
    new Date(Date.now() + 1000 * 60 * 60 * 24 * 3).toISOString().slice(0, 16),
  );
  const [location, setLocation] = useState("");
  const [info, setInfo] = useState("");
  const [capacity, setCapacity] = useState(0);
  const [submitting, setSubmitting] = useState(false);

  const locationBias =
    guild?.primaryLocationLat != null && guild?.primaryLocationLng != null
      ? { lat: guild.primaryLocationLat, lng: guild.primaryLocationLng }
      : undefined;

  const onSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!guild) return;
    setSubmitting(true);
    try {
      const created = await createEvent(guild.id, {
        name,
        description: info,
        ...(location.trim() ? { location } : {}),
        capacity,
        dateTime: new Date(date).toISOString(),
      });
      router.push(`/events/${created.id}`);
    } finally {
      setSubmitting(false);
    }
  };

  const stamp = dateStamp(new Date(date).toISOString());

  return (
    <div className="mx-auto max-w-[820px] px-5 py-6">
      <header className="flex items-center gap-3 mb-5">
        <span className="inline-flex items-center justify-center w-10 h-10 rounded-[10px] bg-leaf border-[1.5px] border-ink shadow-chunky-sm">
          <Peepo size={24} />
        </span>
        <div>
          <span className="text-[13px] font-extrabold tracking-[0.18em] text-mute uppercase">
            NEW EVENT
          </span>
          <h1 className="text-[42px] font-extrabold tracking-[-0.03em] leading-none mt-0.5">
            post something to do
          </h1>
        </div>
      </header>

      {/* live preview — neutral while the backend figures out the category */}
      <div className="relative rounded-[14px] border-[1.5px] border-ink shadow-chunky-md overflow-hidden p-4 flex items-start gap-3 bg-paper3 text-ink">
        <span
          className="absolute opacity-[0.16] select-none pointer-events-none"
          style={{ right: -12, bottom: -36, transform: "rotate(-12deg)" }}
          aria-hidden
        >
          <Peepo size={180} />
        </span>
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
          <span className="inline-flex items-center gap-1.5 rounded-full border-[1.5px] border-ink bg-paper px-3.5 py-1 text-[14px] font-extrabold shadow-chunky-sm text-mute uppercase tracking-[0.08em]">
            category · auto-sorted
          </span>
          <h2 className="mt-1.5 text-[32px] sm:text-[36px] font-extrabold tracking-[-0.03em] leading-[1.05]">
            {name || "your event title"}
          </h2>
          <p className="mt-1 text-[16px] font-semibold">
            {timeLabel(new Date(date).toISOString())} · 📍 {location || "venue"}
          </p>
        </div>
      </div>

      <form onSubmit={onSubmit} className="mt-5 flex flex-col gap-4">
        <Slab className="p-5 flex flex-col gap-4">
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
              <DateTimePicker value={date} onChange={setDate} />
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
              onChange={setLocation}
              placeholder="where?"
              recent={recentVenues}
              locationBias={locationBias}
            />
          </Field>

          <Field label="info">
            <textarea
              value={info}
              onChange={(e) => setInfo(e.target.value)}
              rows={4}
              placeholder="what do people need to know?"
              className={inputCls + " resize-y"}
            />
          </Field>
        </Slab>

        <div className="flex justify-end">
          <Chunky type="submit" variant="leaf" size="lg" disabled={submitting}>
            {submitting
              ? "posting…"
              : "post to #" + (guild?.channel ?? "outings")}
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
