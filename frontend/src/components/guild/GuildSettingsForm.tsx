"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import Link from "next/link";
import { CategoriesArchiveCard } from "@/components/guild/settings/CategoriesArchiveCard";
import { CreationThrottleCard } from "@/components/guild/settings/CreationThrottleCard";
import { DangerZoneCard } from "@/components/guild/settings/DangerZoneCard";
import { PrimaryLocationCard } from "@/components/guild/settings/PrimaryLocationCard";
import { RolesPermissionsCard } from "@/components/guild/settings/RolesPermissionsCard";
import { RsvpEmojiCard } from "@/components/guild/settings/RsvpEmojiCard";
import { StickySaveBar } from "@/components/guild/settings/StickySaveBar";
import {
  kickBotFromGuild,
  updateGuildSettings,
  useCurrentUser,
  useGuildCategories,
  useGuildRoles,
  useGuildSettings,
  useGuilds,
} from "@/lib/hooks";
import { UnauthorizedError, errorRef } from "@/lib/api";
import { toastError } from "@/lib/toast";
import { ErrorRef } from "@/components/ui/ErrorRef";
import {
  fetchPlaceDetails,
  geocodePlace,
  newPlacesSessionToken,
} from "@/lib/places";

type State = {
  primaryLocation: string;
  primaryLocationPlaceId: string | null;
  primaryLocationLat: number | null;
  primaryLocationLng: number | null;
  notifRole: string;
  organiserRole: string;
  anyoneCanCreate: boolean;
  rateLimit: number;
  plannedCategoryId: string | null;
  archivedCategoryId: string | null;
  archiveDays: 7 | 14 | 30 | 90;
  emojiAccepted: string;
  emojiDeclined: string;
  emojiMaybe: string;
};

export function GuildSettingsForm({ guildId }: { guildId: string }) {
  const router = useRouter();
  const { data: user } = useCurrentUser();
  const { data: guilds } = useGuilds();
  const guild = guilds?.find((g) => g.id === guildId) ?? null;
  const { data: settings, isLoading, error } = useGuildSettings(guildId);
  const { data: roles, isLoading: rolesLoading } = useGuildRoles(guildId);
  const { data: categories, isLoading: categoriesLoading } = useGuildCategories(guildId);
  // Lazy useState initializer creates the token exactly once and keeps it
  // stable for the component's lifetime (useMemo offers no such guarantee, and
  // react-hooks/use-memo rejects a non-inline initializer anyway).
  const [sessionToken] = useState(newPlacesSessionToken);

  const [state, setState] = useState<State | null>(null);
  const [initial, setInitial] = useState<State | null>(null);
  const [submitting, setSubmitting] = useState(false);

  // Seed the form from the loaded settings exactly once. Initialising during
  // render (guarded by `state === null`, so it can't loop) avoids the extra
  // render pass an effect would add (react-hooks/set-state-in-effect).
  if (settings && state === null) {
    const snapshot: State = {
      primaryLocation: settings.primaryLocationName ?? "",
      primaryLocationPlaceId: settings.primaryLocationPlaceId ?? null,
      primaryLocationLat: settings.primaryLocationLat ?? null,
      primaryLocationLng: settings.primaryLocationLng ?? null,
      notifRole: settings.eventsRole ?? "events",
      organiserRole: settings.organiserRole ?? "event-organiser",
      anyoneCanCreate: settings.anyoneCanCreate ?? true,
      rateLimit:
        settings.eventCreateRateLimitPerHour ??
        settings.defaultEventCreateRateLimitPerHour ??
        5,
      plannedCategoryId: settings.plannedCategoryId ?? null,
      archivedCategoryId: settings.archivedCategoryId ?? null,
      archiveDays: (settings.archiveDays ?? 90) as 7 | 14 | 30 | 90,
      emojiAccepted: settings.emojiAccepted ?? "✅",
      emojiDeclined: settings.emojiDeclined ?? "❌",
      emojiMaybe: settings.emojiMaybe ?? "❓",
    };
    setState(snapshot);
    setInitial(snapshot);
  }

  useEffect(() => {
    if (user && !user.ownedGuildIds?.includes(guildId)) router.push("/");
  }, [user, router, guildId]);

  if (isLoading && !settings && !error) {
    return <div className="p-8 text-mute">loading…</div>;
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
        {!isUnauthorized && (
          <div className="mx-auto mt-4 max-w-[360px] text-left">
            <ErrorRef info={errorRef(error)} />
          </div>
        )}
        <Link
          href="/"
          className="mt-6 inline-block text-[16px] font-semibold text-mute hover:text-ink"
        >
          ← back
        </Link>
      </div>
    );
  }
  if (!settings || !state || !initial) {
    return <div className="p-8 text-mute">loading…</div>;
  }

  const dirty = JSON.stringify(state) !== JSON.stringify(initial);

  const handleLocationPick = async (placeId: string, displayValue: string) => {
    setState({
      ...state,
      primaryLocation: displayValue,
      primaryLocationPlaceId: placeId,
    });
    fetchPlaceDetails(placeId, sessionToken);
    const coords = await geocodePlace(placeId, sessionToken);
    if (coords) {
      setState((s) =>
        s ? { ...s, primaryLocationLat: coords.lat, primaryLocationLng: coords.lng } : s,
      );
    }
  };

  const onSave = async () => {
    setSubmitting(true);
    try {
      await updateGuildSettings(guildId, {
        primaryLocationPlaceId: state.primaryLocationPlaceId,
        primaryLocationName: state.primaryLocation.trim() || null,
        primaryLocationLat: state.primaryLocationLat,
        primaryLocationLng: state.primaryLocationLng,
        eventsRole: state.notifRole,
        organiserRole: state.organiserRole,
        emojiAccepted: state.emojiAccepted,
        emojiDeclined: state.emojiDeclined,
        emojiMaybe: state.emojiMaybe,
        anyoneCanCreate: state.anyoneCanCreate,
        eventCreateRateLimitPerHour: state.anyoneCanCreate ? state.rateLimit : null,
        plannedCategoryId: state.plannedCategoryId,
        archivedCategoryId: state.archivedCategoryId,
        archiveDays: state.archiveDays,
      });
      setInitial(state);
    } catch (e) {
      // No inline slot once the sticky save bar scrolls out of view — toast it.
      toastError(e);
    } finally {
      setSubmitting(false);
    }
  };

  const onKick = async (confirmGuildName: string) => {
    try {
      await kickBotFromGuild(guildId, confirmGuildName);
    } catch (e) {
      toastError(e);
      return;
    }
    router.push("/");
  };

  const locationBias =
    guild?.primaryLocationLat != null && guild?.primaryLocationLng != null
      ? { lat: guild.primaryLocationLat, lng: guild.primaryLocationLng }
      : undefined;

  const toChipOptions = (entries: { id?: string; name?: string }[] | undefined) =>
    (entries ?? [])
      .filter((e): e is { id: string; name: string } => !!e.id && !!e.name);

  return (
    <div className="px-14 pt-8 pb-14">
      <header className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="text-[36px] font-extrabold tracking-[-0.03em] lowercase">
            {guild?.name ?? "server config"}
          </h1>
          <p className="text-[13.5px] font-semibold text-mute mt-1">
            edit settings for {guild?.name?.toLowerCase() ?? "this server"}
            {typeof guild?.members === "number" ? ` · ${guild.members} members` : ""}
          </p>
        </div>
        <Link
          href="/"
          className="inline-flex items-center gap-1.5 text-[16px] font-semibold text-mute hover:text-ink"
        >
          ← back
        </Link>
      </header>

      <div className="flex gap-5 items-start">
        <div className="flex-[1.2] flex flex-col gap-5">
          <RsvpEmojiCard
            value={{
              accept: state.emojiAccepted,
              decline: state.emojiDeclined,
              maybe: state.emojiMaybe,
            }}
            onChange={(v) =>
              setState({
                ...state,
                emojiAccepted: v.accept,
                emojiDeclined: v.decline,
                emojiMaybe: v.maybe,
              })
            }
          />
          <CategoriesArchiveCard
            plannedCategoryId={state.plannedCategoryId}
            archivedCategoryId={state.archivedCategoryId}
            archiveDays={state.archiveDays}
            categories={toChipOptions(categories)}
            ready={!categoriesLoading}
            onPlannedChange={(id) => setState({ ...state, plannedCategoryId: id })}
            onArchivedChange={(id) => setState({ ...state, archivedCategoryId: id })}
            onArchiveDaysChange={(d) =>
              setState({ ...state, archiveDays: d as 7 | 14 | 30 | 90 })
            }
          />
        </div>

        <div className="flex-1 flex flex-col gap-5">
          <PrimaryLocationCard
            value={state.primaryLocation}
            onChange={(v) =>
              setState({
                ...state,
                primaryLocation: v,
                primaryLocationPlaceId: null,
                primaryLocationLat: null,
                primaryLocationLng: null,
              })
            }
            onPick={handleLocationPick}
            locationBias={locationBias}
          />
          <RolesPermissionsCard
            notifRole={state.notifRole}
            organiserRole={state.organiserRole}
            anyoneCanCreate={state.anyoneCanCreate}
            roles={toChipOptions(roles)}
            ready={!rolesLoading}
            onNotifRoleChange={(n) => setState({ ...state, notifRole: n })}
            onOrganiserRoleChange={(n) => setState({ ...state, organiserRole: n })}
            onAnyoneCanCreateChange={(v) => setState({ ...state, anyoneCanCreate: v })}
          />
          {state.anyoneCanCreate && (
            <CreationThrottleCard
              rateLimit={state.rateLimit}
              onRateLimitChange={(n) => setState({ ...state, rateLimit: n })}
            />
          )}
          <DangerZoneCard guildName={guild?.name ?? ""} onKick={onKick} />
        </div>
      </div>

      <StickySaveBar
        dirty={dirty}
        submitting={submitting}
        onDiscard={() => setState(initial)}
        onSave={onSave}
      />
    </div>
  );
}
