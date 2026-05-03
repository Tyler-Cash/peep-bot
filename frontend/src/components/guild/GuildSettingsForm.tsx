"use client";

import { useRouter } from "next/navigation";
import { useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { Chunky } from "@/components/ui/Chunky";
import { Slab } from "@/components/ui/Slab";
import { LocationAutocomplete } from "@/components/ui/LocationAutocomplete";
import {
  updateGuildSettings,
  useCurrentUser,
  useGuildSettings,
  useGuilds,
} from "@/lib/hooks";
import { UnauthorizedError } from "@/lib/api";
import {
  fetchPlaceDetails,
  geocodePlace,
  newPlacesSessionToken,
} from "@/lib/places";

type TabKey = "roles" | "emoji" | "defaults";
const TABS: { key: TabKey; label: string }[] = [
  { key: "roles", label: "Roles & channels" },
  { key: "emoji", label: "RSVP emoji" },
  { key: "defaults", label: "Defaults & limits" },
];

function readTabFromHash(): TabKey {
  if (typeof window === "undefined") return "roles";
  const m = window.location.hash.match(/tab=(roles|emoji|defaults)/);
  return (m?.[1] as TabKey) ?? "roles";
}

export function GuildSettingsForm({ guildId }: { guildId: string }) {
  const router = useRouter();
  const { data: user } = useCurrentUser();
  const { data: guilds } = useGuilds();
  const guild = guilds?.find((g) => g.id === guildId) ?? null;
  const { data: settings, isLoading, error } = useGuildSettings(guildId);
  const sessionToken = useMemo(newPlacesSessionToken, []);

  const [activeTab, setActiveTab] = useState<TabKey>("roles");
  useEffect(() => {
    setActiveTab(readTabFromHash());
    const onHash = () => setActiveTab(readTabFromHash());
    window.addEventListener("hashchange", onHash);
    return () => window.removeEventListener("hashchange", onHash);
  }, []);

  const [primaryLocation, setPrimaryLocation] = useState("");
  const [primaryLocationPlaceId, setPrimaryLocationPlaceId] = useState<string | null>(null);
  const [primaryLocationLat, setPrimaryLocationLat] = useState<number | null>(null);
  const [primaryLocationLng, setPrimaryLocationLng] = useState<number | null>(null);
  const [eventsRole, setEventsRole] = useState("events");
  const [organiserRole, setOrganiserRole] = useState("event-organiser");
  const [separatorChannel, setSeparatorChannel] = useState("");
  const [emojiAccepted, setEmojiAccepted] = useState("✅");
  const [emojiDeclined, setEmojiDeclined] = useState("❌");
  const [emojiMaybe, setEmojiMaybe] = useState("❓");
  const [rateLimit, setRateLimit] = useState<number | null>(null);
  const [rateLimitDefault, setRateLimitDefault] = useState<number>(5);
  const initialOrganiserRole = useRef<string | null>(null);
  const [initialized, setInitialized] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (settings && !initialized) {
      setPrimaryLocation(settings.primaryLocationName ?? "");
      setPrimaryLocationPlaceId(settings.primaryLocationPlaceId ?? null);
      setPrimaryLocationLat(settings.primaryLocationLat ?? null);
      setPrimaryLocationLng(settings.primaryLocationLng ?? null);
      setEventsRole(settings.eventsRole ?? "events");
      setOrganiserRole(settings.organiserRole ?? "event-organiser");
      setSeparatorChannel(settings.separatorChannel ?? "");
      setEmojiAccepted(settings.emojiAccepted ?? "✅");
      setEmojiDeclined(settings.emojiDeclined ?? "❌");
      setEmojiMaybe(settings.emojiMaybe ?? "❓");
      setRateLimit(settings.eventCreateRateLimitPerHour ?? null);
      setRateLimitDefault(settings.defaultEventCreateRateLimitPerHour ?? 5);
      initialOrganiserRole.current = settings.organiserRole ?? "event-organiser";
      setInitialized(true);
    }
  }, [settings, initialized]);

  useEffect(() => {
    if (user && !user.ownedGuildIds?.includes(guildId)) router.push("/");
  }, [user, router, guildId]);

  const handleLocationChange = (value: string) => {
    setPrimaryLocation(value);
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

  if (isLoading && !settings && !error) {
    return <div className="mx-auto max-w-[820px] p-8 text-mute">loading…</div>;
  }

  if (error) {
    const isUnauthorized = error instanceof UnauthorizedError;
    return (
      <div className="mx-auto max-w-[640px] px-4 py-16 text-center">
        <h1 className="text-[32px] font-extrabold tracking-[-0.04em]">
          {isUnauthorized ? "access denied" : "couldn't load settings"}
        </h1>
        <p className="mt-3 text-[17px] text-mute">
          {isUnauthorized
            ? "You don't have permission to view server settings."
            : "Something went wrong. Try refreshing the page."}
        </p>
        <Link href="/" className="mt-6 inline-block text-[16px] font-semibold text-mute hover:text-ink">
          ← back
        </Link>
      </div>
    );
  }

  if (!settings) {
    return <div className="mx-auto max-w-[820px] p-8 text-mute">loading…</div>;
  }

  const onSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (organiserRole !== initialOrganiserRole.current) {
      const ok = window.confirm(
        `After this change, only members with the role "${organiserRole}" will be able to organise events (cancel, recategorize, create private channels). Continue?`
      );
      if (!ok) return;
    }
    setSubmitting(true);
    try {
      await updateGuildSettings(guildId, {
        primaryLocationPlaceId,
        primaryLocationName: primaryLocation.trim() || null,
        primaryLocationLat,
        primaryLocationLng,
        eventsRole,
        organiserRole,
        separatorChannel: separatorChannel.trim() || null,
        emojiAccepted,
        emojiDeclined,
        emojiMaybe,
        eventCreateRateLimitPerHour: rateLimit,
      });
      router.push("/");
    } finally {
      setSubmitting(false);
    }
  };

  const locationBias =
    guild?.primaryLocationLat != null && guild?.primaryLocationLng != null
      ? { lat: guild.primaryLocationLat, lng: guild.primaryLocationLng }
      : undefined;

  const switchTab = (key: TabKey) => {
    setActiveTab(key);
    if (typeof window !== "undefined") {
      window.history.replaceState(null, "", `#tab=${key}`);
    }
  };

  return (
    <div className="mx-auto max-w-[820px] px-4 sm:px-5 py-6 pb-28">
      <div className="flex items-center justify-between mb-5">
        <Link
          href="/"
          className="inline-flex items-center gap-1.5 text-[18px] font-semibold text-mute hover:text-ink"
        >
          ← back
        </Link>
      </div>

      <header className="flex items-center gap-3 mb-5">
        <span className="inline-flex items-center justify-center w-10 h-10 rounded-chip border-[1.5px] border-ink shadow-rest bg-paper2 text-[18px]">
          ⚙️
        </span>
        <div>
          <span className="text-[11px] font-extrabold tracking-[0.18em] text-mute uppercase">
            SERVER SETTINGS
          </span>
          <h1 className="text-[26px] sm:text-[36px] font-extrabold tracking-[-0.03em] leading-tight mt-0.5 break-words">
            {guild?.name ?? "server config"}
          </h1>
        </div>
      </header>

      <nav
        role="tablist"
        className="flex gap-2 overflow-x-auto -mx-4 px-4 mb-4 sticky top-0 bg-paper z-10 py-2"
      >
        {TABS.map((t) => {
          const active = t.key === activeTab;
          return (
            <button
              key={t.key}
              role="tab"
              aria-selected={active}
              type="button"
              onClick={() => switchTab(t.key)}
              className={
                "shrink-0 px-4 py-2 rounded-chip border-[1.5px] border-ink text-[14px] font-extrabold tracking-[-0.01em] " +
                (active ? "bg-ink text-paper shadow-rest" : "bg-paper2 text-ink hover:bg-paper3")
              }
            >
              {t.label}
            </button>
          );
        })}
      </nav>

      <form onSubmit={onSubmit} className="flex flex-col gap-4">
        {activeTab === "roles" && (
          <Slab className="p-5 flex flex-col gap-4">
            <Field label="events role">
              <input
                className="w-full px-3 py-2 rounded-input border-[1.5px] border-ink"
                value={eventsRole}
                onChange={(e) => setEventsRole(e.target.value)}
              />
              <p className="text-[11.5px] text-mute font-semibold mt-1">
                Role pinged when new events are created.
              </p>
            </Field>
            <Field label="event organiser role">
              <input
                className="w-full px-3 py-2 rounded-input border-[1.5px] border-ink"
                value={organiserRole}
                onChange={(e) => setOrganiserRole(e.target.value)}
              />
              <p className="text-[11.5px] text-mute font-semibold mt-1">
                Members with this role can cancel events, recategorize, remove attendees, and create private channels.
              </p>
            </Field>
            <Field label="separator channel">
              <input
                className="w-full px-3 py-2 rounded-input border-[1.5px] border-ink"
                value={separatorChannel}
                onChange={(e) => setSeparatorChannel(e.target.value)}
                placeholder="(none)"
              />
            </Field>
          </Slab>
        )}

        {activeTab === "emoji" && (
          <Slab className="p-5 flex flex-col gap-4">
            <Field label="accepted emoji">
              <input
                className="w-full px-3 py-2 rounded-input border-[1.5px] border-ink"
                value={emojiAccepted}
                onChange={(e) => setEmojiAccepted(e.target.value)}
              />
              <p className="text-[11.5px] text-mute font-semibold mt-1">
                Unicode emoji or the name of a custom emoji in this guild.
              </p>
            </Field>
            <Field label="declined emoji">
              <input
                className="w-full px-3 py-2 rounded-input border-[1.5px] border-ink"
                value={emojiDeclined}
                onChange={(e) => setEmojiDeclined(e.target.value)}
              />
            </Field>
            <Field label="maybe emoji">
              <input
                className="w-full px-3 py-2 rounded-input border-[1.5px] border-ink"
                value={emojiMaybe}
                onChange={(e) => setEmojiMaybe(e.target.value)}
              />
            </Field>
          </Slab>
        )}

        {activeTab === "defaults" && (
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

            <Field label="event-create rate limit">
              <label className="flex items-center gap-2 text-[14px] font-semibold">
                <input
                  type="checkbox"
                  className="w-4 h-4 border-[1.5px] border-ink rounded-sm"
                  checked={rateLimit === null}
                  onChange={(e) =>
                    setRateLimit(e.target.checked ? null : (rateLimit ?? rateLimitDefault))
                  }
                />
                Use server default ({rateLimitDefault} / hour)
              </label>
              {rateLimit !== null && (
                <div className="flex gap-2 flex-wrap mt-2">
                  {[3, 5, 7, 10].map((n) => {
                    const active = rateLimit === n;
                    return (
                      <button
                        key={n}
                        type="button"
                        onClick={() => setRateLimit(n)}
                        className={
                          "px-4 py-2 rounded-chip border-[1.5px] border-ink text-[14px] font-extrabold " +
                          (active ? "bg-ink text-paper shadow-rest" : "bg-paper2 text-ink hover:bg-paper3")
                        }
                      >
                        {n} / hour
                      </button>
                    );
                  })}
                </div>
              )}
              <p className="text-[11.5px] text-mute font-semibold mt-1">
                Caps how many events members of this server can create each hour.
              </p>
            </Field>
          </Slab>
        )}

        <div className="fixed bottom-0 left-0 right-0 border-t-[1.5px] border-ink bg-paper px-4 py-3 z-20">
          <div className="mx-auto max-w-[820px] flex items-center justify-between">
            <Link
              href="/"
              className="inline-flex items-center gap-1.5 text-[16px] font-semibold text-mute hover:text-ink"
            >
              cancel
            </Link>
            <Chunky type="submit" variant="leaf" size="lg" disabled={submitting}>
              {submitting ? "saving…" : "save settings"}
            </Chunky>
          </div>
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
