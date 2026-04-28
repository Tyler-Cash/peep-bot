"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import Link from "next/link";
import { Chunky } from "@/components/ui/Chunky";
import { DatePicker, TimePicker } from "@/components/ui/DateTimePicker";
import { LocationAutocomplete } from "@/components/ui/LocationAutocomplete";
import { Stepper } from "@/components/ui/Stepper";
import { ApiError, BackendUnreachable, UnauthorizedError } from "@/lib/api";
import { dateToLocalInput } from "@/lib/format";
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
  const [error, setError] = useState<string | null>(null);

  const locationBias =
    guild?.primaryLocationLat != null && guild?.primaryLocationLng != null
      ? { lat: guild.primaryLocationLat, lng: guild.primaryLocationLng }
      : undefined;

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
    return <div className="mx-auto max-w-[960px] p-8 text-mute">loading…</div>;
  }

  const onSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!guild) return;
    setSubmitting(true);
    setError(null);
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
    } catch (e) {
      if (e instanceof UnauthorizedError) return;
      if (e instanceof BackendUnreachable) {
        setError("can't reach the server — check your connection and try again");
      } else if (e instanceof ApiError) {
        setError(e.message);
      } else {
        setError("something went wrong saving these changes");
      }
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="mx-auto max-w-[960px] px-4 sm:px-5 py-6 sm:py-8">
      <Link
        href={`/events/${id}`}
        className="inline-flex items-center gap-1.5 mb-4 text-[16px] font-semibold text-mute hover:text-ink"
      >
        ← back to event
      </Link>

      <form
        onSubmit={onSubmit}
        className="flex flex-col gap-3.5 bg-white border-[1.5px] border-ink rounded-card shadow-hero p-4 sm:p-6"
      >
        <h2 className="text-[28px] font-extrabold tracking-[-0.02em] leading-none lowercase mb-1">
          edit event
        </h2>
        <p className="text-[14.5px] text-mute leading-[1.45] -mt-2 mb-1">
          update the details for <b className="font-extrabold">{data.name}</b>.
        </p>

        <Field label="event name">
          <input
            value={name}
            onChange={(e) => setName(e.target.value)}
            required
            className={inputCls}
          />
        </Field>

        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
          <Field label="date">
            <DatePicker value={date || null} onChange={setDate} />
          </Field>
          <Field label="time">
            <TimePicker value={date || null} onChange={setDate} />
          </Field>
        </div>

        <div className="grid grid-cols-1 sm:grid-cols-[1fr_150px] gap-3">
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

        {error && (
          <div
            role="alert"
            className="rounded-chip border-[1.5px] border-ink bg-rose-50 text-ink px-[14px] py-2.5 text-[14.5px] font-semibold leading-[1.4]"
          >
            {error}
          </div>
        )}

        <div className="flex items-center justify-end gap-3 pt-1.5 mt-1 border-t border-dashed border-ink/20">
          <Link
            href={`/events/${id}`}
            className="text-[16px] font-semibold text-mute hover:text-ink"
          >
            cancel
          </Link>
          <Chunky type="submit" variant="leaf" disabled={submitting}>
            {submitting ? "saving…" : "save changes"}
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
