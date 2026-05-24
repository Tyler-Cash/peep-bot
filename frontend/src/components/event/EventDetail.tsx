"use client";

import { useState } from "react";
import Image from "next/image";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { Chunky } from "@/components/ui/Chunky";
import { Slab } from "@/components/ui/Slab";
import { CatTag } from "@/components/ui/CatTag";
import { CountdownChip } from "@/components/ui/CountdownChip";
import { ConfirmModal } from "@/components/ui/ConfirmModal";
import { DateTile } from "@/components/ui/DateTile";
import { RsvpGroup } from "./RsvpGroup";
import { ActivityLog, type ActivityEvent } from "./ActivityLog";
import { categoryMeta } from "@/lib/categories";
import { dateStamp, seededTilt, timeLabel } from "@/lib/format";
import {
  cancelEvent,
  createPrivateChannel,
  recategorizeEvent,
  removeAttendee,
  submitRsvp,
  useActiveGuild,
  useCurrentUser,
  useEvent,
} from "@/lib/hooks";
import type { Attendee, RsvpStatus } from "@/lib/types";
import { ApiError, errorRef } from "@/lib/api";
import { toastError } from "@/lib/toast";
import { ErrorRef } from "@/components/ui/ErrorRef";
import { PencilIcon } from "@/components/icons/PencilIcon";

// Discord snowflakes encode their creation time in the high bits, with epoch
// 2015-01-01. We use that to derive the host post's "posted at" timestamp
// without needing a separate field on the event DTO.
const DISCORD_EPOCH_MS = 1420070400000;

function snowflakeToIso(snowflake: string | undefined): string | null {
  if (!snowflake) return null;
  try {
    const ms = Number(BigInt(snowflake) >> BigInt(22)) + DISCORD_EPOCH_MS;
    if (!Number.isFinite(ms)) return null;
    return new Date(ms).toISOString();
  } catch {
    return null;
  }
}

function buildActivityEvents(input: {
  accepted: Attendee[];
  maybe: Attendee[];
  declined: Attendee[];
  host?: string;
  hostAvatarUrl?: string;
  messageId?: string;
  dateTime: string;
}): ActivityEvent[] {
  const events: ActivityEvent[] = [];

  if (input.host) {
    const at =
      snowflakeToIso(input.messageId) ?? input.dateTime;
    events.push({
      id: input.messageId ? `host:${input.messageId}` : "host",
      who: input.host,
      avatarUrl: input.hostAvatarUrl ?? null,
      kind: "host",
      at,
    });
  }

  const push = (list: Attendee[], kind: "going" | "maybe" | "declined") => {
    for (const a of list) {
      if (!a.instant) continue;
      events.push({
        id: `${kind}:${a.snowflake ?? a.name}:${a.instant}`,
        who: a.name,
        avatarUrl: a.avatarUrl ?? null,
        hue: a.hue,
        kind,
        at: a.instant,
      });
    }
  };
  push(input.accepted, "going");
  push(input.maybe, "maybe");
  push(input.declined, "declined");

  return events;
}

export function EventDetail({ id }: { id: string }) {
  const router = useRouter();
  const { data, mutate, isLoading, error } = useEvent(id);
  const { data: me } = useCurrentUser();
  const guild = useActiveGuild();
  const [pendingRemove, setPendingRemove] = useState<Attendee | null>(null);
  const [showCancelModal, setShowCancelModal] = useState(false);
  const [showPrivateChannelModal, setShowPrivateChannelModal] = useState(false);
  const [isRecategorizing, setIsRecategorizing] = useState(false);

  const handleRecategorize = async () => {
    if (!guild) return;
    setIsRecategorizing(true);
    try {
      await recategorizeEvent(guild.id, id);
    } catch (e) {
      toastError(e);
    } finally {
      setIsRecategorizing(false);
    }
  };

  if (isLoading && !data && !error) {
    return <div className="mx-auto max-w-[1200px] p-8 text-mute">loading…</div>;
  }

  if (error) {
    const is404 = error instanceof ApiError && error.status === 404;
    return (
      <div className="mx-auto max-w-[640px] px-4 py-16 text-center">
        <h1 className="text-[32px] font-extrabold tracking-[-0.04em]">
          {is404 ? "event not found" : "couldn't load event"}
        </h1>
        <p className="mt-3 text-[17px] text-mute">
          {is404
            ? "This event doesn't exist or may have been removed."
            : "Something went wrong. Try refreshing the page."}
        </p>
        {!is404 && (
          <div className="mx-auto mt-4 max-w-[360px] text-left">
            <ErrorRef info={errorRef(error)} />
          </div>
        )}
        <Link href="/" className="mt-6 inline-block text-[16px] font-semibold text-mute hover:text-ink">
          ← back to events
        </Link>
      </div>
    );
  }

  if (!data) {
    return <div className="mx-auto max-w-[1200px] p-8 text-mute">loading…</div>;
  }

  const isCancelled = data.displayState === "cancelled" || data.state === "CANCELLED";
  const isPast = new Date(data.dateTime) < new Date();
  const cat = categoryMeta(data.category);
  const stamp = dateStamp(data.dateTime);
  const tileTilt = seededTilt(`tile-${data.id}`, 1.6);

  // EventDetailDto lists are optional in the generated type (backend DTO has no
  // required annotation); default to [] so the rest of the component stays clean.
  const accepted = (data.accepted ?? []) as Attendee[];
  const maybe = (data.maybe ?? []) as Attendee[];
  const declined = (data.declined ?? []) as Attendee[];

  const activityEvents = buildActivityEvents({
    accepted,
    maybe,
    declined,
    host: data.host,
    hostAvatarUrl: data.hostAvatarUrl,
    messageId: data.messageId,
    dateTime: data.dateTime,
  });

  const meStatus: RsvpStatus | null =
    accepted.some((a) => a.snowflake === me?.discordId)
      ? "going"
      : maybe.some((a) => a.snowflake === me?.discordId)
        ? "maybe"
        : declined.some((a) => a.snowflake === me?.discordId)
          ? "declined"
          : null;

  const setStatus = async (status: RsvpStatus) => {
    if (!guild || !me || isCancelled || data.completed) return;
    mutate(
      (prev) => {
        if (!prev) return prev;
        const strip = (l: Attendee[]) =>
          l.filter((a) => a.snowflake !== me.discordId);
        const attendee: Attendee = {
          snowflake: me.discordId ?? null,
          name: me.username ?? "",
          instant: new Date().toISOString(),
          avatarUrl: me.avatarUrl ?? null,
          hue: "#7BC24F",
        };
        const next = {
          ...prev,
          accepted: strip((prev.accepted ?? []) as Attendee[]),
          maybe: strip((prev.maybe ?? []) as Attendee[]),
          declined: strip((prev.declined ?? []) as Attendee[]),
        };
        if (status === "going") next.accepted.push(attendee);
        if (status === "maybe") next.maybe.push(attendee);
        if (status === "declined") next.declined.push(attendee);
        return next;
      },
      { revalidate: false },
    );
    try {
      await submitRsvp(guild.id, data.id, status);
    } catch (e) {
      // The optimistic update above is now wrong — revalidate to snap back to server truth.
      mutate();
      toastError(e);
    }
  };

  const isAdmin = me?.organiserGuildIds?.includes(guild?.id ?? "") ?? false;

  const confirmRemove = async () => {
    if (!pendingRemove || !guild || !data) return;
    const attendee = pendingRemove;
    setPendingRemove(null);
    mutate(
      (prev) => {
        if (!prev) return prev;
        const strip = (l: Attendee[]) =>
          l.filter(
            (a) => !(a.snowflake === attendee.snowflake && a.name === attendee.name),
          );
        return {
          ...prev,
          accepted: strip((prev.accepted ?? []) as Attendee[]),
          maybe: strip((prev.maybe ?? []) as Attendee[]),
          declined: strip((prev.declined ?? []) as Attendee[]),
        };
      },
      { revalidate: false },
    );
    try {
      await removeAttendee(guild.id, data.id, attendee.snowflake, attendee.name);
    } catch (e) {
      // Optimistic removal failed — revalidate to restore the attendee, then surface it.
      mutate();
      toastError(e);
    }
  };

  const handleCancel = async () => {
    if (!guild) return;
    try {
      await cancelEvent(guild.id, data.id);
    } catch (e) {
      toastError(e);
      return;
    }
    setShowCancelModal(false);
    router.push("/");
  };

  const handleCreatePrivateChannel = async () => {
    if (!guild) return;
    try {
      await createPrivateChannel(guild.id, data.id);
    } catch (e) {
      toastError(e);
      return;
    }
    setShowPrivateChannelModal(false);
  };

  const rsvpHeadline =
    meStatus === "going"
      ? "you're in"
      : meStatus === "maybe"
        ? "you said maybe"
        : meStatus === "declined"
          ? "you can't make it"
          : "no rsvp yet";

  return (
    <>
      <div className="mx-auto max-w-[1200px] px-4 sm:px-5 py-5">
        <Link
          href="/"
          className="inline-flex items-center gap-1.5 text-[18px] font-semibold text-mute hover:text-ink"
        >
          ← back to events
        </Link>

      <div className="mt-4 grid grid-cols-1 lg:grid-cols-[minmax(0,1fr)_340px] gap-7">
        <div className="flex flex-col gap-5 min-w-0">
          {/* hero */}
          <div
            className="relative rounded-hero border-[1.5px] border-ink shadow-hero overflow-hidden p-4 sm:p-6"
            style={{ background: cat.bg, color: cat.ink }}
          >
          {isCancelled && (
              <div className="absolute inset-0 bg-ink/60 flex items-center justify-center z-10">
              <span className="text-[22px] font-extrabold tracking-[0.08em] text-white bg-ink/80 px-5 py-2 rounded-chip border-[1.5px] border-white/30 uppercase">
                cancelled
              </span>
              </div>
          )}
          {cat.image ? (
            <Image
              src={cat.image}
              alt=""
              aria-hidden
              width={220}
              height={220}
              className="absolute select-none pointer-events-none opacity-[0.22]"
              style={{ right: -24, bottom: -70, transform: "rotate(-12deg)" }}
            />
          ) : (
            <span
              className="absolute text-[220px] leading-none opacity-[0.18] select-none pointer-events-none"
              style={{ right: -24, bottom: -70, transform: "rotate(-12deg)" }}
              aria-hidden
            >
              {cat.emoji}
            </span>
          )}
            <div className="relative flex items-start gap-3 sm:gap-4">
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-2 flex-wrap">
                  <CatTag category={data.category} displayState={data.displayState} />
                  <CountdownChip iso={data.dateTime} />
                </div>
                <h1 className="mt-2 text-[32px] sm:text-[56px] font-extrabold tracking-[-0.05em] leading-[0.98] break-words">
                  {data.name}
                </h1>
                <p className="mt-2 text-[15px] sm:text-[18px] font-semibold">
                  {stamp.weekday} · {timeLabel(data.dateTime)} · 🎤 organized by {data.host}
                  {data.hostUsername && data.hostUsername !== data.host && (
                    <span className="text-[13px] sm:text-[16px] text-ink/70 font-medium ml-1">@{data.hostUsername}</span>
                  )}
                </p>
              </div>
              <div className="flex flex-col items-end gap-2 shrink-0">
                <DateTile iso={data.dateTime} tilt={tileTilt} variant="desktop" />
                {!isPast && !isCancelled && (
                  <Link
                    href={`/events/${id}/edit`}
                    aria-label="edit event"
                    className="inline-flex items-center gap-2 rounded-chip border-[1.5px] border-current bg-white/80 px-3 sm:px-4 py-1.5 text-[14px] sm:text-[16px] font-extrabold tracking-[-0.01em] shadow-rest hover:bg-white/95 transition-colors"
                  >
                    <PencilIcon className="w-4 h-4" />
                    <span className="hidden sm:inline">edit event</span>
                    <span className="sm:hidden">edit</span>
                  </Link>
                )}
              </div>
            </div>
          </div>

            {/* your rsvp */}
            <Slab className="p-5">
              <span className="text-[13px] font-extrabold tracking-[0.18em] text-mute uppercase">
                your rsvp
              </span>
              <p className="mt-1 text-[22px] sm:text-[26px] font-extrabold tracking-[-0.02em]">{rsvpHeadline}</p>
              {!isCancelled && !data.completed && (
                <div className="mt-3 flex gap-2 flex-wrap">
                  <Chunky
                    variant={meStatus === "going" ? "leaf" : "paper"}
                    onClick={() => setStatus("going")}
                  >
                    <Image src="/peepos/peepo-going.png" alt="" aria-hidden width={20} height={20} className="inline shrink-0 mr-1" />
                    going
                  </Chunky>
                  <Chunky
                    variant={meStatus === "maybe" ? "leaf" : "paper"}
                    onClick={() => setStatus("maybe")}
                  >
                    <Image src="/peepos/peepo-maybe.png" alt="" aria-hidden width={20} height={20} className="inline shrink-0 mr-1" />
                    maybe
                  </Chunky>
                  <Chunky
                    variant={meStatus === "declined" ? "leaf" : "paper"}
                    onClick={() => setStatus("declined")}
                  >
                    <Image src="/peepos/peepo-no.png" alt="" aria-hidden width={20} height={20} className="inline shrink-0 mr-1" />
                    can&apos;t
                  </Chunky>
                </div>
              )}
              {(isCancelled || data.completed) && (
                <p className="mt-2 text-[13px] text-mute">
                  {isCancelled ? "this event was cancelled" : "rsvps are closed"}
                </p>
              )}
            </Slab>

            {/* the plan */}
            {data.description && (<Slab className="p-5">
              <span className="text-[13px] font-extrabold tracking-[0.18em] text-mute uppercase">
                the plan
              </span>
              <p className="mt-2 text-[19px] leading-[1.6] text-ink2 whitespace-pre-line break-words">
                {data.description}
              </p>
            </Slab>)}

          {/* where */}
          {data.location && (
            <Slab className="p-5">
              <span className="text-[13px] font-extrabold tracking-[0.18em] text-mute uppercase">
                where
              </span>
              <a
                href={`https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(data.location)}`}
                target="_blank"
                rel="noreferrer"
                className="mt-2 flex items-center gap-2 text-[19px] font-semibold text-ink hover:text-ink2 group"
              >
                <span className="text-[20px] shrink-0">📍</span>
                <span className="underline underline-offset-2 decoration-ink/30 group-hover:decoration-ink/60 transition-colors break-words min-w-0">
                  {data.location}
                </span>
              </a>
            </Slab>
          )}

            {/* guest list */}
            <Slab className="p-5 flex flex-col gap-4">
              <span className="text-[13px] font-extrabold tracking-[0.18em] text-mute uppercase">
                the guest list
              </span>
              <RsvpGroup label="going" emoji="✅" image="/peepos/peepo-going.png" people={accepted} onRemove={isAdmin && !data.completed ? setPendingRemove : undefined} />
              <RsvpGroup label="maybe" emoji="🤔" image="/peepos/peepo-maybe.png" people={maybe}
              onRemove={isAdmin && !data.completed ? setPendingRemove : undefined}
            />
            <RsvpGroup
              label="can't make it"
              emoji="❌"
              image="/peepos/peepo-no.png"
              people={declined}
              onRemove={isAdmin && !data.completed ? setPendingRemove : undefined} />
            </Slab>
          </div>

          <aside className="lg:sticky lg:top-24 h-fit flex flex-col gap-4">
            <div className="rounded-card border-[1.5px] border-ink bg-ink text-paper p-5 shadow-rest">
              <span className="text-[13px] font-extrabold tracking-[0.18em] text-muteDk uppercase">
                KEEP CHATTING IN
              </span>
              <p className="mt-1 text-[24px] sm:text-[28px] font-extrabold tracking-[-0.02em] break-words">{guild?.name ?? "events"}</p>
              <a
                href={`https://discord.com/channels/${guild?.id ?? ""}/${data.channelId ?? ""}/${data.messageId ?? ""}`}
                target="_blank"
                rel="noreferrer"
                className="mt-3 inline-flex"
              >
                <Chunky variant="leaf">
                  open in Discord →
                </Chunky>
              </a>
            </div>

            {isAdmin && !isCancelled && (
              <div className="rounded-card border-[1.5px] border-ink bg-paper2 p-5 shadow-rest flex flex-col gap-2">
                <span className="text-[11px] font-extrabold tracking-[0.18em] text-mute uppercase">
                  admin
                </span>
                {!isPast && (
                  <>
                    <Chunky
                      variant="paper"
                      size="sm"
                      className="w-full justify-center"
                      onClick={() => setShowPrivateChannelModal(true)}
                      disabled={data.hasPrivateChannel}
                    >
                      {data.hasPrivateChannel ? "🔒 private channel active" : "🔒 create private channel"}
                    </Chunky>
                    <Chunky
                      variant="danger"
                      size="sm"
                      className="w-full justify-center"
                      onClick={() => setShowCancelModal(true)}
                    >
                      ✕ cancel event
                    </Chunky>
                  </>
                )}
                <Chunky
                  variant="paper"
                  size="sm"
                  className="w-full justify-center"
                  onClick={handleRecategorize}
                  disabled={isRecategorizing}
                >
                  {isRecategorizing ? "🔄 categorizing…" : "🔄 re-categorize"}
                </Chunky>
              </div>
            )}

            <ActivityLog
              events={activityEvents}
              channelName={guild?.name ?? "discord"}
            />
          </aside>
        </div>

      {pendingRemove && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-ink/40 backdrop-blur-sm"
          onClick={() => setPendingRemove(null)}
        >
          <div
            className="bg-white rounded-hero border-[1.5px] border-ink shadow-hero p-6 max-w-sm w-full mx-4"
            onClick={(e) => e.stopPropagation()}
          >
            <h2 className="text-[26px] font-extrabold tracking-[-0.02em]">Remove attendee?</h2>
            <p className="mt-2 text-[18px] text-ink2">
              This will remove <strong>{pendingRemove.name}</strong> from the guest list.
            </p>
            <div className="mt-5 flex gap-3 justify-end">
              <Chunky variant="paper" size="sm" onClick={() => setPendingRemove(null)}>
                cancel
              </Chunky>
              <Chunky variant="ink" size="sm" onClick={confirmRemove}>
                remove
              </Chunky>
            </div>
          </div>
        </div>
      )}
      </div>

      {showPrivateChannelModal && (
        <ConfirmModal
          title="create private channel?"
          message={
            <span>
              This creates a Discord channel only accepted attendees can see.
              {" "}Keep most planning in the public event channel — the private channel is for
              logistics that need to stay between confirmed attendees only.
            </span>
          }
          confirmLabel="create channel"
          confirmVariant="leaf"
          onConfirm={handleCreatePrivateChannel}
          onCancel={() => setShowPrivateChannelModal(false)}
        />
      )}

      {showCancelModal && (
        <ConfirmModal
          title="cancel this event?"
          message="Cancelling will lock RSVPs and notify all attendees. This cannot be undone."
          confirmLabel="cancel event"
          confirmVariant="danger"
          onConfirm={handleCancel}
          onCancel={() => setShowCancelModal(false)}
        />
      )}
    </>
  );
}
