"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import Link from "next/link";
import { Chunky } from "@/components/ui/Chunky";
import { Slab } from "@/components/ui/Slab";
import { LocationAutocomplete } from "@/components/ui/LocationAutocomplete";
import {
  updateGuildSettings,
  useActiveGuild,
  useCurrentUser,
  useGuildSettings,
} from "@/lib/hooks";
import {
  fetchPlaceDetails,
  geocodePlace,
  newPlacesSessionToken,
} from "@/lib/places";
import { useMemo } from "react";

export function GuildSettingsForm({ guildId }: { guildId: string }) {
  const router = useRouter();
  const { data: user } = useCurrentUser();
  const activeGuild = useActiveGuild();
  const { data: settings, isLoading } = useGuildSettings(guildId);
  const sessionToken = useMemo(newPlacesSessionToken, []);

  const [primaryLocation, setPrimaryLocation] = useState("");
  const [primaryLocationPlaceId, setPrimaryLocationPlaceId] = useState<string | null>(null);
  const [primaryLocationLat, setPrimaryLocationLat] = useState<number | null>(null);
  const [primaryLocationLng, setPrimaryLocationLng] = useState<number | null>(null);
  const [initialized, setInitialized] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  // Populate from loaded settings once
  useEffect(() => {
    if (settings && !initialized) {
      setPrimaryLocation(settings.primaryLocationName ?? "");
      setPrimaryLocationPlaceId(settings.primaryLocationPlaceId ?? null);
      setPrimaryLocationLat(settings.primaryLocationLat ?? null);
      setPrimaryLocationLng(settings.primaryLocationLng ?? null);
      setInitialized(true);
    }
  }, [settings, initialized]);

  // Redirect non-admins
  useEffect(() => {
    if (user && !user.admin) router.push("/");
  }, [user, router]);

  const handleLocationChange = (value: string) => {
    setPrimaryLocation(value);
    // Clear resolved coords when user edits the field manually
    setPrimaryLocationPlaceId(null);
    setPrimaryLocationLat(null);
    setPrimaryLocationLng(null);
  };

  const handleLocationPick = async (placeId: string, displayValue: string) => {
    setPrimaryLocation(displayValue);
    setPrimaryLocationPlaceId(placeId);
    fetchPlaceDetails(placeId, sessionToken);
    const coords = await geocodePlace(placeId, sessionToken);
    if (coords) {
      setPrimaryLocationLat(coords.lat);
      setPrimaryLocationLng(coords.lng);
    }
  };

  if (isLoading || !settings) {
    return <div className="mx-auto max-w-[820px] p-8 text-mute">loading…</div>;
  }

  const onSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setSubmitting(true);
    try {
      await updateGuildSettings(guildId, {
        primaryLocationPlaceId,
        primaryLocationName: primaryLocation.trim() || null,
        primaryLocationLat,
        primaryLocationLng,
      });
      router.push("/");
    } finally {
      setSubmitting(false);
    }
  };

  const locationBias =
    activeGuild?.primaryLocationLat != null && activeGuild?.primaryLocationLng != null
      ? { lat: activeGuild.primaryLocationLat, lng: activeGuild.primaryLocationLng }
      : undefined;

  return (
    <div className="mx-auto max-w-[820px] px-5 py-6">
      <div className="flex items-center justify-between mb-5">
        <Link
          href="/"
          className="inline-flex items-center gap-1.5 text-[18px] font-semibold text-mute hover:text-ink"
        >
          ← back
        </Link>
      </div>

      <header className="flex items-center gap-3 mb-5">
        <span className="inline-flex items-center justify-center w-10 h-10 rounded-[10px] border-[1.5px] border-ink shadow-chunky-sm bg-paper2 text-[18px]">
          ⚙️
        </span>
        <div>
          <span className="text-[11px] font-extrabold tracking-[0.18em] text-mute uppercase">
            SERVER SETTINGS
          </span>
          <h1 className="text-[36px] font-extrabold tracking-[-0.03em] leading-none mt-0.5">
            {activeGuild?.name ?? "server config"}
          </h1>
        </div>
      </header>

      <form onSubmit={onSubmit} className="flex flex-col gap-4">
        <Slab className="p-5 flex flex-col gap-4">
          <Field label="primary location">
            <LocationAutocomplete
              value={primaryLocation}
              onChange={handleLocationChange}
              onPick={handleLocationPick}
              placeholder="e.g. Melbourne, VIC"
              locationBias={locationBias}
            />
            <p className="text-[11.5px] text-mute font-semibold mt-1">
              Used to bias venue search results towards your group&apos;s area.
            </p>
          </Field>
        </Slab>

        <div className="flex items-center justify-between">
          <Link
            href="/"
            className="inline-flex items-center gap-1.5 text-[18px] font-semibold text-mute hover:text-ink"
          >
            cancel
          </Link>
          <Chunky type="submit" variant="leaf" size="lg" disabled={submitting}>
            {submitting ? "saving…" : "save settings"}
          </Chunky>
        </div>
      </form>
    </div>
  );
}

function Field({
  label,
  children,
}: {
  label: string;
  children: React.ReactNode;
}) {
  return (
    <label className="flex flex-col gap-1.5">
      <span className="text-[11px] font-extrabold tracking-[0.18em] text-mute uppercase">
        {label}
      </span>
      {children}
    </label>
  );
}
