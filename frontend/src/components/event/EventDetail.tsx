"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { Chunky } from "@/components/ui/Chunky";
import { Slab } from "@/components/ui/Slab";
import { CatTag } from "@/components/ui/CatTag";
import { CountdownChip } from "@/components/ui/CountdownChip";
import { ConfirmModal } from "@/components/ui/ConfirmModal";
import { RsvpGroup } from "./RsvpGroup";
import { categoryMeta } from "@/lib/categories";
import { dateStamp, timeLabel } from "@/lib/format";
import {
  cancelEvent,
  createPrivateChannel,
  removeAttendee,
  submitRsvp,
  useActiveGuild,
  useCurrentUser,
  useEvent,
} from "@/lib/hooks";
import type { Attendee, RsvpStatus } from "@/lib/types";
import { PencilIcon } from "@/components/icons/PencilIcon";

export function EventDetail({ id }: { id: string }) {
  const router = useRouter();
  const { data, mutate, isLoading } = useEvent(id);
  const { data: me } = useCurrentUser();
  const guild = useActiveGuild();
  const [pendingRemove, setPendingRemove] = useState<Attendee | null>(null);
  const [showCancelModal, setShowCancelModal] = useState(false);
  const [showPrivateChannelModal, setShowPrivateChannelModal] = useState(false);

  if (isLoading || !data) {
    return <div className="mx-auto max-w-[1200px] p-8 text-mute">loading…</div>;
  }

  const isCancelled = data.state === "CANCELLED";
  const cat = categoryMeta(data.category);
  const stamp = dateStamp(data.dateTime);
  const meStatus: RsvpStatus | null =
    data.accepted.some((a) => a.snowflake === me?.discordId)
      ? "going"
      : data.maybe.some((a) => a.snowflake === me?.discordId)
        ? "maybe"
        : data.declined.some((a) => a.snowflake === me?.discordId)
          ? "declined"
          : null;

  const setStatus = async (status: RsvpStatus) => {
    if (!guild || !me || isCancelled || data.completed) return;
    mutate(
      (prev) => {
        if (!prev) return prev;
        const strip = (l: typeof prev.accepted) =>
          l.filter((a) => a.snowflake !== me.discordId);
        const attendee = {
          snowflake: me.discordId,
          name: me.username,
          instant: new Date().toISOString(),
          avatarUrl: me.avatarUrl ?? null,
          hue: "#7BC24F",
        };
        const next = {
          ...prev,
          accepted: strip(prev.accepted),
          maybe: strip(prev.maybe),
          declined: strip(prev.declined),
        };
        if (status === "going") next.accepted.push(attendee);
        if (status === "maybe") next.maybe.push(attendee);
        if (status === "declined") next.declined.push(attendee);
        return next;
      },
      { revalidate: false },
    );
    await submitRsvp(guild.id, data.id, status);
  };

  const isAdmin = me?.admin ?? false;

  const confirmRemove = async () => {
    if (!pendingRemove || !guild || !data) return;
    const attendee = pendingRemove;
    setPendingRemove(null);
    mutate(
      (prev) => {
        if (!prev) return prev;
        const strip = (l: typeof prev.accepted) =>
          l.filter(
            (a) => !(a.snowflake === attendee.snowflake && a.name === attendee.name),
          );
        return {
          ...prev,
          accepted: strip(prev.accepted),
          maybe: strip(prev.maybe),
          declined: strip(prev.declined),
        };
      },
      { revalidate: false },
    );
    await removeAttendee(guild.id, data.id, attendee.snowflake, attendee.name);
  };

  const handleCancel = async () => {
    if (!guild) return;
    await cancelEvent(guild.id, data.id);
    setShowCancelModal(false);
    router.push("/");
  };

  const handleCreatePrivateChannel = async () => {
    if (!guild) return;
    await createPrivateChannel(guild.id, data.id);
    setShowPrivateChannelModal(false);
  };

  const rsvpHeadline =
    meStatus === "going"
      ? "✅ you're in"
      : meStatus === "maybe"
        ? "🤔 you said maybe"
        : meStatus === "declined"
          ? "❌ you can't make it"
          : "no rsvp yet";

  return (
    <>
      <div className="mx-auto max-w-[1200px] px-5 py-5">
        <Link
          href="/"
          className="inline-flex items-center gap-1.5 text-[18px] font-semibold text-mute hover:text-ink"
        >
          ← back to #{guild?.channel ?? "outings"}
        </Link>

      <div className="mt-4 grid grid-cols-1 lg:grid-cols-[1fr_340px] gap-7">
        <div className="flex flex-col gap-5">
          {/* hero */}
          <div
            className="relative rounded-[16px] border-[1.5px] border-ink shadow-chunky-lg overflow-hidden p-6"
            style={{ background: cat.bg, color: cat.ink }}
          >
          {isCancelled && (
              <div className="absolute inset-0 bg-ink/60 flex items-center justify-center z-10">
              <span className="text-[22px] font-extrabold tracking-[0.08em] text-white bg-ink/80 px-5 py-2 rounded-[10px] border-[1.5px] border-white/30 uppercase">
                cancelled
              </span>
              </div>
          )}
          <span
              className="absolute text-[220px] leading-none opacity-[0.18] select-none pointer-events-none"
              style={{ right: -24, bottom: -70, transform: "rotate(-12deg)" }}
              aria-hidden
          >
            {cat.emoji}
          </span>
            <div className="relative flex items-start gap-4">
              <div className="flex flex-col items-center justify-center rounded-[12px] bg-white/95 border-[1.5px] border-ink w-[86px] py-2 shadow-chunky-sm shrink-0">
                <span className="text-[13px] font-extrabold tracking-[0.14em]">{stamp.month}</span>
                <span className="text-[36px] font-extrabold leading-none tabular-nums">{stamp.day}</span>
                <span className="text-[13px] font-extrabold tracking-[0.14em] uppercase">
                  {stamp.weekday}
                </span>
              </div>
              <div className="min-w-0 flex-1">
                <div className="flex items-center justify-between gap-2">
                  <div className="flex items-center gap-2">
                      <CatTag category={data.category} state={data.state} />
                    <CountdownChip iso={data.dateTime} />
                  </div>
                  <Link
                    href={`/events/${id}/edit`}
                    className="inline-flex items-center gap-2 rounded-[10px] border-[1.5px] border-current bg-white/80 px-4 py-1.5 text-[16px] font-extrabold tracking-[-0.01em] shadow-chunky-sm hover:bg-white/95 transition-colors"
                  >
                    <PencilIcon className="w-4 h-4" />
                    edit event
                  </Link>
                </div>
                <h1 className="mt-2 text-[48px] sm:text-[56px] font-extrabold tracking-[-0.05em] leading-[0.98]">
                  {data.name}
                </h1>
                <p className="mt-2 text-[18px] font-semibold">
                  {stamp.weekday} · {timeLabel(data.dateTime)} · 🎤 organized by {data.host}
                  {data.hostUsername && data.hostUsername !== data.host && (
                    <span className="text-[16px] text-ink/70 font-medium ml-1">@{data.hostUsername}</span>
                  )}
                </p>
              </div>
            </div>
          </div>

            {/* your rsvp */}
            <Slab className="p-5">
              <span className="text-[13px] font-extrabold tracking-[0.18em] text-mute uppercase">
                your rsvp
              </span>
              <p className="mt-1 text-[26px] font-extrabold tracking-[-0.02em]">{rsvpHeadline}</p>
              {!isCancelled && !data.completed && (
                <div className="mt-3 flex gap-2 flex-wrap">
                  <Chunky
                    variant={meStatus === "going" ? "leaf" : "paper"}
                    onClick={() => setStatus("going")}
                  >
                    ✅ going
                  </Chunky>
                  <Chunky
                    variant={meStatus === "maybe" ? "leaf" : "paper"}
                    onClick={() => setStatus("maybe")}
                  >
                    🤔 maybe
                  </Chunky>
                  <Chunky
                    variant={meStatus === "declined" ? "leaf" : "paper"}
                    onClick={() => setStatus("declined")}
                  >
                    ❌ can&apos;t
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
              <p className="mt-2 text-[19px] leading-[1.6] text-ink2 whitespace-pre-line">
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
                <span className="underline underline-offset-2 decoration-ink/30 group-hover:decoration-ink/60 transition-colors">
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
              <RsvpGroup label="going" emoji="✅" people={data.accepted}onRemove={isAdmin ? setPendingRemove : undefined} />
              <RsvpGroup label="maybe" emoji="🤔" people={data.maybe}
              onRemove={isAdmin ? setPendingRemove : undefined}
            />
            <RsvpGroup
              label="can't make it"
              emoji="❌"
              people={data.declined}
              onRemove={isAdmin ? setPendingRemove : undefined} />
            </Slab>
          </div>

          <aside className="lg:sticky lg:top-24 h-fit flex flex-col gap-4">
            <div className="rounded-[14px] border-[1.5px] border-ink bg-ink text-paper p-5 shadow-chunky-md">
              <span className="text-[13px] font-extrabold tracking-[0.18em] text-muteDk uppercase">
                KEEP CHATTING IN
              </span>
              <p className="mt-1 text-[28px] font-extrabold tracking-[-0.02em]"># {guild?.channel ?? "outings"}</p>
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

            {me?.admin && !isCancelled && (
              <div className="rounded-[14px] border-[1.5px] border-ink bg-paper2 p-5 shadow-chunky-md flex flex-col gap-2">
                <span className="text-[11px] font-extrabold tracking-[0.18em] text-mute uppercase">
                  admin
                </span>
                <Link href={`/events/${id}/edit`}>
                  <Chunky variant="paper" size="sm" className="w-full justify-center">
                    ✏️ edit event
                  </Chunky>
                </Link>
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
              </div>
            )}
          </aside>
        </div>

      {pendingRemove && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-ink/40 backdrop-blur-sm"
          onClick={() => setPendingRemove(null)}
        >
          <div
            className="bg-white rounded-[16px] border-[1.5px] border-ink shadow-chunky-lg p-6 max-w-sm w-full mx-4"
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
