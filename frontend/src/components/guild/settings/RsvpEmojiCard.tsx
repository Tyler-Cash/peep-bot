"use client";

import { EMOJI_GOING, EMOJI_NO, EMOJI_MAYBE } from "./emojiCatalog";

type Value = { accept: string; decline: string; maybe: string };

const SLOTS = [
  { key: "accept" as const, eyebrow: "going", bg: "bg-rsvpGoing", catalog: EMOJI_GOING },
  { key: "decline" as const, eyebrow: "not going", bg: "bg-rsvpNo", catalog: EMOJI_NO },
  { key: "maybe" as const, eyebrow: "maybe", bg: "bg-rsvpMaybe", catalog: EMOJI_MAYBE },
];

export function RsvpEmojiCard({
  value,
  onChange,
}: {
  value: Value;
  onChange: (v: Value) => void;
}) {
  return (
    <section className="bg-paper2 border-[1.5px] border-ink rounded-[16px] shadow-[5px_5px_0_#0E100D] p-6">
      <h2 className="text-[24px] font-extrabold tracking-[-0.03em] lowercase">rsvp emoji</h2>
      <p className="text-[13.5px] font-semibold text-mute mt-1">
        three reactions members tap. peepbot tallies them every minute.
      </p>
      <div className="grid grid-cols-3 gap-2.5 mt-4">
        {SLOTS.map((slot) => (
          <div key={slot.key} className={`${slot.bg} border-[1.5px] border-ink rounded-[12px] p-3`}>
            <p className="text-[11.5px] font-extrabold tracking-[0.18em] text-mute uppercase">
              {slot.eyebrow}
            </p>
            <p className="text-[32px] mt-1" aria-label={`${slot.eyebrow} slot selection`}>
              {value[slot.key]}
            </p>
            <div
              className="grid grid-cols-4 gap-1 mt-2"
              aria-label={`${slot.eyebrow} swatches`}
            >
              {slot.catalog.map((e) => {
                const active = e === value[slot.key];
                return (
                  <button
                    key={e}
                    type="button"
                    onClick={() => onChange({ ...value, [slot.key]: e })}
                    className={
                      "rounded-chip border-[1.5px] border-ink py-1 text-[18px] " +
                      (active ? "bg-leaf" : "bg-paper hover:bg-paper2")
                    }
                  >
                    {e}
                  </button>
                );
              })}
            </div>
          </div>
        ))}
      </div>
    </section>
  );
}
