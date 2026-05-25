"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { Chunky } from "@/components/ui/Chunky";
import { DatePicker, TimePicker } from "@/components/ui/DateTimePicker";
import { LocationAutocomplete } from "@/components/ui/LocationAutocomplete";
import { PickedLocationCard } from "@/components/ui/PickedLocationCard";
import type { PlaceSuggestion } from "@/lib/places";
import { Stepper } from "@/components/ui/Stepper";
import { InlineError } from "@/components/ui/InlineError";
import {
  ApiError,
  UnauthorizedError,
  describeError,
  errorRef,
  type ErrorRef as ErrorRefInfo,
} from "@/lib/api";
import { dateToLocalInput } from "@/lib/format";
import { createEvent, useActiveGuild, useRecentLocations } from "@/lib/hooks";

// EventDto field names (camelCase from backend) that map to a visible input.
// Anything not listed falls through to the global error banner.
const FIELD_INPUTS = new Set(["name", "description", "location", "capacity"]);

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
  const [pickedPlace, setPickedPlace] = useState<PlaceSuggestion | null>(null);
  const [info, setInfo] = useState("");
  const [capacity, setCapacity] = useState(0);
  const [submitting, setSubmitting] = useState(false);
  const [globalError, setGlobalError] = useState<string | null>(null);
  const [globalErrorRef, setGlobalErrorRef] = useState<ErrorRefInfo | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  const locationBias =
    guild?.primaryLocationLat != null && guild?.primaryLocationLng != null
      ? { lat: guild.primaryLocationLat, lng: guild.primaryLocationLng }
      : undefined;

  const onSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!guild) return;
    setSubmitting(true);
    setGlobalError(null);
    setGlobalErrorRef(null);
    setFieldErrors({});
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
    } catch (e) {
      if (e instanceof UnauthorizedError) return;
      // Only 400s carry per-field validation errors worth mapping to inputs; everything
      // else (429, 5xx, unreachable) goes through the shared describeError mapping.
      if (e instanceof ApiError && e.status === 400) {
        const { fieldErrors: fe, globalError: ge } = splitApiError(e);
        setFieldErrors(fe);
        setGlobalError(ge);
        setGlobalErrorRef(ge ? errorRef(e) : null);
      } else {
        const { message, ref } = describeError(e);
        setGlobalError(message);
        setGlobalErrorRef(ref);
      }
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="mx-auto max-w-[960px] px-4 sm:px-5 py-6 sm:py-8">
      <form
        onSubmit={onSubmit}
        className="flex flex-col gap-3.5 bg-white border-[1.5px] border-ink rounded-card shadow-hero p-4 sm:p-6"
      >
        <h2 className="text-[28px] font-extrabold tracking-[-0.02em] leading-none lowercase mb-1">
          new event
        </h2>
        <p className="text-[14.5px] text-mute leading-[1.45] -mt-2 mb-1">
          plan something cool with friends in <b className="font-extrabold">{guild?.name ?? "your server"}</b>.
        </p>

        <Field label="event name" error={fieldErrors.name}>
          <input
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="trivia at the dog & duck"
            required
            className={inputCls}
          />
        </Field>

        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
          <Field label="date">
            <DatePicker value={date} onChange={setDate} />
          </Field>
          <Field label="time">
            <TimePicker value={date} onChange={setDate} />
          </Field>
        </div>

        <div className="grid grid-cols-1 sm:grid-cols-[1fr_150px] gap-3">
          <Field label="where" error={fieldErrors.location}>
            {pickedPlace ? (
              <PickedLocationCard
                place={pickedPlace}
                onClear={() => {
                  setPickedPlace(null);
                  setLocationPlaceId("");
                }}
              />
            ) : (
              <LocationAutocomplete
                value={location}
                onChange={(v) => { setLocation(v); setLocationPlaceId(""); }}
                onPick={(placeId, displayValue, suggestion) => {
                  setLocationPlaceId(placeId);
                  setLocation(displayValue);
                  setPickedPlace(suggestion);
                }}
                placeholder="search venues…"
                recent={recentVenues}
                locationBias={locationBias}
              />
            )}
          </Field>
          <Field label="cap" error={fieldErrors.capacity}>
            <Stepper value={capacity} onChange={setCapacity} />
          </Field>
        </div>

        <Field label="description" error={fieldErrors.description}>
          <textarea
            value={info}
            onChange={(e) => setInfo(e.target.value)}
            rows={6}
            placeholder="a few short lines — what to expect, what to bring, who's coming, when to show up"
            className={areaCls}
          />
        </Field>

        {globalError && <InlineError message={globalError} info={globalErrorRef} />}

        <div className="flex items-center justify-end gap-3 pt-1.5 mt-1 border-t border-dashed border-ink/20">
          <Chunky type="submit" variant="leaf" disabled={submitting}>
            {submitting ? "posting…" : "post event"}
          </Chunky>
        </div>
      </form>
    </div>
  );
}

// Maps a 400 validation response onto visible inputs. Field errors the form has an input
// for land next to that input; anything else falls through to the global error banner.
function splitApiError(e: ApiError): {
  fieldErrors: Record<string, string>;
  globalError: string | null;
} {
  const body = e.body as { fieldErrors?: Array<Record<string, unknown>> } | null;
  const raw = body?.fieldErrors ?? [];
  const mapped: Record<string, string> = {};
  const leftover: string[] = [];
  for (const f of raw) {
    const field = (f.field ?? f.path) as string | undefined;
    const msg = (f.defaultMessage ?? f.message) as string | undefined;
    if (!msg) continue;
    if (field && FIELD_INPUTS.has(field)) {
      mapped[field] = msg;
    } else if (field) {
      leftover.push(`${field}: ${msg}`);
    } else {
      leftover.push(msg);
    }
  }
  if (Object.keys(mapped).length > 0 || leftover.length > 0) {
    return {
      fieldErrors: mapped,
      globalError: leftover.length > 0 ? leftover.join("; ") : null,
    };
  }
  return { fieldErrors: {}, globalError: e.message };
}

const inputCls =
  "w-full h-12 rounded-chip border-[1.5px] border-ink bg-white px-[14px] text-[16px] font-semibold text-ink placeholder:font-medium placeholder:text-mute shadow-rest focus:outline-none";

const areaCls =
  "w-full rounded-chip border-[1.5px] border-ink bg-white px-[14px] py-3 text-[16px] font-medium text-ink placeholder:text-mute leading-[1.45] min-h-[140px] shadow-rest focus:outline-none resize-y";

function Field({
  label,
  children,
  error,
}: {
  label: string;
  children: React.ReactNode;
  error?: string;
}) {
  return (
    <label className="flex flex-col gap-1.5">
      <span className="text-[12px] font-extrabold tracking-[0.16em] text-mute uppercase">
        {label}
      </span>
      {children}
      {error && (
        <span className="text-[12.5px] font-semibold text-rose-700 leading-[1.35]">
          {error}
        </span>
      )}
    </label>
  );
}
