"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { Chunky } from "@/components/ui/Chunky";
import { DatePicker, TimePicker } from "@/components/ui/DateTimePicker";
import { LocationAutocomplete } from "@/components/ui/LocationAutocomplete";
import { Stepper } from "@/components/ui/Stepper";
import { dateToLocalInput } from "@/lib/format";
import { createEvent, useActiveGuild, useRecentLocations } from "@/lib/hooks";

export function CreateEventForm() {
  const router = useRouter();
  const guild = useActiveGuild();
  const recentVenues = useRecentLocations();
  const [name, setName] = useState("");
  const [date, setDate] = useState(() =>
    dateToLocalInput(new Date(Date.now() + 1000 * 60 * 60 * 24 * 3)),
  );
  const [location, setLocation] = useState("");
  const [locationPlaceId, setLocationPlaceId] = useState("");
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
        ...(locationPlaceId ? { locationPlaceId } : {}),
        capacity,
        dateTime: new Date(date).toISOString(),
      });
      router.push(`/events/${created.id}`);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="mx-auto max-w-[960px] px-5 py-8">
      <form
        onSubmit={onSubmit}
        className="flex flex-col gap-3.5 bg-white border-[1.5px] border-ink rounded-card shadow-hero p-6"
      >
        <h2 className="text-[28px] font-extrabold tracking-[-0.02em] leading-none lowercase mb-1">
          new event
        </h2>
        <p className="text-[14.5px] text-mute leading-[1.45] -mt-2 mb-1">
          plan something cool with friends in <b className="font-extrabold">#{guild?.channel ?? "outings"}</b>.
        </p>

        <Field label="event name">
          <input
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="trivia at the dog & duck"
            required
            className={inputCls}
          />
        </Field>

        <div className="grid grid-cols-2 gap-3">
          <Field label="date">
            <DatePicker value={date} onChange={setDate} />
          </Field>
          <Field label="time">
            <TimePicker value={date} onChange={setDate} />
          </Field>
        </div>

        <div className="grid grid-cols-[1fr_150px] gap-3">
          <Field label="where">
            <LocationAutocomplete
              value={location}
              onChange={(v) => { setLocation(v); setLocationPlaceId(""); }}
              onPick={(placeId) => setLocationPlaceId(placeId)}
              placeholder="search venues…"
              recent={recentVenues}
              locationBias={locationBias}
            />
          </Field>
          <Field label="cap">
            <Stepper value={capacity} onChange={setCapacity} />
          </Field>
        </div>

        <Field label="description">
          <textarea
            value={info}
            onChange={(e) => setInfo(e.target.value)}
            rows={6}
            placeholder="a few short lines — what to expect, what to bring, who's coming, when to show up"
            className={areaCls}
          />
        </Field>

        <div className="flex items-center justify-end gap-3 pt-1.5 mt-1 border-t border-dashed border-ink/20">
          <Chunky type="submit" variant="leaf" disabled={submitting}>
            {submitting ? "posting…" : "post event"}
          </Chunky>
        </div>
      </form>
    </div>
  );
}

const inputCls =
  "w-full h-12 rounded-chip border-[1.5px] border-ink bg-white px-[14px] text-[16px] font-semibold text-ink placeholder:font-medium placeholder:text-mute shadow-rest focus:outline-none";

const areaCls =
  "w-full rounded-chip border-[1.5px] border-ink bg-white px-[14px] py-3 text-[16px] font-medium text-ink placeholder:text-mute leading-[1.45] min-h-[140px] shadow-rest focus:outline-none resize-y";

function Field({
  label,
  children,
}: {
  label: string;
  children: React.ReactNode;
}) {
  return (
    <label className="flex flex-col gap-1.5">
      <span className="text-[12px] font-extrabold tracking-[0.16em] text-mute uppercase">
        {label}
      </span>
      {children}
    </label>
  );
}
