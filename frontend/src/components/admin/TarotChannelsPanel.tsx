"use client";

import { useState } from "react";
import { updateTarotChannels, useAdminTarot } from "@/lib/hooks";

// Channel allowlist for /tarot, shown under the tarot toggle. No channels selected = anywhere.
export function TarotChannelsPanel({ guildId }: { guildId: string }) {
  const { data, error } = useAdminTarot(guildId);
  const [busy, setBusy] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);

  if (error) {
    return <p className="text-[14px] text-mute font-medium">couldn&apos;t load tarot channels.</p>;
  }
  if (!data) {
    return <p className="text-[14px] text-mute font-medium">loading channels…</p>;
  }

  const allowed = new Set(data.allowedChannelIds);

  async function save(next: string[]) {
    setBusy(true);
    setSaveError(null);
    try {
      await updateTarotChannels(guildId, next);
    } catch (e) {
      setSaveError(e instanceof Error ? e.message : "save failed");
    } finally {
      setBusy(false);
    }
  }

  function toggle(id: string) {
    const next = new Set(allowed);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    void save([...next]);
  }

  return (
    <div>
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <span className="eyebrow">allowed channels</span>
        <span className="text-[13px] text-mute font-semibold">
          {data.drawsLast7Days} draw{data.drawsLast7Days === 1 ? "" : "s"} in the last 7 days
        </span>
      </div>
      <p className="mt-0.5 text-[14px] text-ink2 font-medium">
        {allowed.size === 0
          ? "/tarot works in every channel. pick channels to restrict it."
          : `/tarot only works in ${allowed.size} channel${allowed.size === 1 ? "" : "s"}.`}
      </p>
      {data.availableChannels.length === 0 ? (
        <p className="mt-2 text-[14px] text-mute font-medium">
          no text channels visible — is the bot connected to this guild?
        </p>
      ) : (
        <div className="mt-2.5 flex flex-wrap gap-2" role="group" aria-label="tarot channels">
          {data.availableChannels.map((c) => {
            const on = allowed.has(c.id);
            return (
              <button
                key={c.id}
                type="button"
                aria-pressed={on}
                disabled={busy}
                onClick={() => toggle(c.id)}
                className="px-3 py-1.5 rounded-full border-[1.5px] border-ink text-[14px] font-bold disabled:opacity-60"
                style={{ background: on ? "#7BC24F" : "#FFFFFF", transition: "background 120ms" }}
              >
                #{c.name}
              </button>
            );
          })}
          {allowed.size > 0 && (
            <button
              type="button"
              disabled={busy}
              onClick={() => void save([])}
              className="px-3 py-1.5 text-[14px] font-bold text-mute underline disabled:opacity-60"
            >
              allow everywhere
            </button>
          )}
        </div>
      )}
      {saveError && <p className="mt-2 text-[14px] text-red-700 font-semibold">{saveError}</p>}
    </div>
  );
}
