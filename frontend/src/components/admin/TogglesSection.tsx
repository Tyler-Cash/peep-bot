"use client";

import { useState } from "react";
import { Slab } from "@/components/ui/Slab";
import {
  setEventCreationEnabled,
  updateGuildFeatures,
  useEventCreationState,
  type AdminGuild,
} from "@/lib/hooks";

type FeatureKey =
  | "immichEnabled"
  | "googleAutocompleteEnabled"
  | "rewindEnabled"
  | "contractsEnabled";

type FeatureDef = {
  key: FeatureKey;
  label: string;
  desc: string;
  group: "content" | "channels" | "rsvp";
};

const FEATURES: FeatureDef[] = [
  {
    key: "rewindEnabled",
    label: "rewind",
    desc: "monthly recap posted back to the server",
    group: "content",
  },
  {
    key: "immichEnabled",
    label: "gallery",
    desc: "photo albums per event, posted to the channel after completion",
    group: "content",
  },
  {
    key: "googleAutocompleteEnabled",
    label: "AI-suggested venues",
    desc: "Google Places suggestions when creating an event",
    group: "rsvp",
  },
  {
    key: "contractsEnabled",
    label: "prediction contracts",
    desc: "lets members place predictions on event outcomes",
    group: "channels",
  },
];

const GROUPS: Array<{ id: FeatureDef["group"]; label: string; desc: string }> = [
  {
    id: "content",
    label: "content surfaces",
    desc: "what peepbot publishes back to the server",
  },
  {
    id: "channels",
    label: "channels & lifecycle",
    desc: "how event channels are created and torn down",
  },
  {
    id: "rsvp",
    label: "rsvp behavior",
    desc: "how going / maybe / declined work",
  },
];

export function TogglesSection({ activeGuild }: { activeGuild: AdminGuild | null }) {
  const { data: eventCreation, mutate: refetchEventCreation } = useEventCreationState();
  const [pending, setPending] = useState<FeatureKey | "event-creation" | null>(null);

  if (!activeGuild) {
    return (
      <div className="max-w-[1080px] mx-auto px-5 pt-6 pb-16 text-mute">
        Select a guild to manage feature flags.
      </div>
    );
  }

  const onCount = FEATURES.filter((f) => activeGuild[f.key]).length;

  async function flip(key: FeatureKey, value: boolean) {
    setPending(key);
    try {
      await updateGuildFeatures(activeGuild!.guildId, { [key]: value });
    } finally {
      setPending(null);
    }
  }

  return (
    <div className="max-w-[1080px] mx-auto px-5 pt-6 pb-16">
      <div className="mb-5">
        <span className="eyebrow">feature toggles · {activeGuild.name ?? activeGuild.guildId}</span>
        <h1 className="mt-1 text-[40px] sm:text-[48px] font-extrabold tracking-[-0.04em] lowercase leading-none">
          what peepbot does here.
        </h1>
        <p className="mt-2 text-[16px] text-ink2 font-medium">
          {onCount}/{FEATURES.length} features on. changes apply per-guild and take effect on the
          next operation.
        </p>
      </div>

      {GROUPS.map((g) => {
        const features = FEATURES.filter((f) => f.group === g.id);
        if (features.length === 0) return null;
        return (
          <Slab key={g.id} className="p-0 overflow-hidden mb-4">
            <div className="px-4 py-3.5 border-b-[1.5px] border-ink bg-paper2">
              <span className="eyebrow">{g.label}</span>
              <p className="mt-0.5 text-[14px] text-ink2 font-semibold">{g.desc}</p>
            </div>
            {features.map((f, i) => (
              <div
                key={f.key}
                className="grid grid-cols-[1fr_auto] items-center gap-4 px-4 py-4 border-t-[1.5px] border-ink/10 first:border-t-0"
                style={i === 0 ? { borderTopWidth: 0 } : undefined}
              >
                <div>
                  <p className="text-[17px] font-extrabold tracking-[-0.01em]">{f.label}</p>
                  <p className="mt-0.5 text-[14px] text-mute font-medium">{f.desc}</p>
                </div>
                <Toggle
                  on={!!activeGuild[f.key]}
                  busy={pending === f.key}
                  onChange={(v) => flip(f.key, v)}
                />
              </div>
            ))}
          </Slab>
        );
      })}

      <Slab className="p-0 overflow-hidden">
        <div className="px-4 py-3.5 border-b-[1.5px] border-ink bg-paper2">
          <span className="eyebrow">global · maintenance</span>
          <p className="mt-0.5 text-[14px] text-ink2 font-semibold">
            applies across every guild peepbot is in
          </p>
        </div>
        <div className="grid grid-cols-[1fr_auto] items-center gap-4 px-4 py-4">
          <div>
            <p className="text-[17px] font-extrabold tracking-[-0.01em]">accept new events</p>
            <p className="mt-0.5 text-[14px] text-mute font-medium">
              when off, the bot rejects /event create across every guild — useful during incidents.
            </p>
          </div>
          <Toggle
            on={!!eventCreation?.enabled}
            busy={pending === "event-creation"}
            onChange={async (v) => {
              setPending("event-creation");
              try {
                await setEventCreationEnabled(v);
                await refetchEventCreation();
              } finally {
                setPending(null);
              }
            }}
          />
        </div>
      </Slab>
    </div>
  );
}

function Toggle({
  on,
  busy,
  onChange,
}: {
  on: boolean;
  busy?: boolean;
  onChange: (v: boolean) => void;
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={on}
      disabled={busy}
      onClick={() => onChange(!on)}
      className="relative w-[54px] h-[30px] rounded-full border-[1.5px] border-ink shadow-rest p-0 disabled:opacity-60"
      style={{
        background: on ? "#7BC24F" : "#EEE8DA",
        transition: "background 120ms",
      }}
    >
      <span
        className="absolute top-[1.5px] w-[23px] h-[23px] rounded-full border-[1.5px] border-ink bg-white inline-flex items-center justify-center text-[12px]"
        style={{ left: on ? 24 : 1.5, transition: "left 120ms" }}
      >
        {on ? "✓" : ""}
      </span>
    </button>
  );
}
