"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { Chunky } from "@/components/ui/Chunky";
import { Slab } from "@/components/ui/Slab";
import { CatTag } from "@/components/ui/CatTag";
import { Peepo } from "@/components/Peepo";
import { CATEGORIES, categoryMeta } from "@/lib/categories";
import { dateStamp, timeLabel } from "@/lib/format";
import { createEvent, useActiveGuild } from "@/lib/hooks";
import type { Category } from "@/lib/types";

export function CreateEventForm() {
  const router = useRouter();
  const guild = useActiveGuild();
  const [name, setName] = useState("");
  const [category, setCategory] = useState<Category>("food");
  const [date, setDate] = useState(() => new Date(Date.now() + 1000 * 60 * 60 * 24 * 3).toISOString().slice(0, 16));
  const [venue, setVenue] = useState("");
  const [city, setCity] = useState("");
  const [description, setDescription] = useState("");
  const [capacity, setCapacity] = useState(0);
  const [submitting, setSubmitting] = useState(false);

  const onSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!guild) return;
    setSubmitting(true);
    try {
      const created = await createEvent(guild.id, {
        name,
        category,
        description,
        location: venue,
        city,
        capacity,
        dateTime: new Date(date).toISOString(),
      });
      router.push(`/events/${created.id}`);
    } finally {
      setSubmitting(false);
    }
  };

  const stamp = dateStamp(new Date(date).toISOString());
  const cat = categoryMeta(category);

  return (
    <div className="mx-auto max-w-[820px] px-5 py-6">
      <header className="flex items-center gap-3 mb-5">
        <span className="inline-flex items-center justify-center w-10 h-10 rounded-[10px] bg-leaf border-[1.5px] border-ink shadow-chunky-sm">
          <Peepo size={24} />
        </span>
        <div>
          <span className="text-[11px] font-extrabold tracking-[0.18em] text-mute uppercase">
            NEW EVENT
          </span>
          <h1 className="text-[36px] font-extrabold tracking-[-0.03em] leading-none mt-0.5">
            post something to do
          </h1>
        </div>
      </header>

      {/* live preview */}
      <div
        className="relative rounded-[14px] border-[1.5px] border-ink shadow-chunky-md overflow-hidden p-4 flex items-start gap-3"
        style={{ background: cat.bg, color: cat.ink }}
      >
        <span
          className="absolute text-[140px] leading-none opacity-[0.2] select-none pointer-events-none"
          style={{ right: -8, bottom: -40, transform: "rotate(-12deg)" }}
          aria-hidden
        >
          {cat.emoji}
        </span>
        <div className="flex flex-col items-center justify-center rounded-[10px] bg-white/90 border-[1.5px] border-ink px-3 py-2 w-[72px] shrink-0 shadow-chunky-sm">
          <span className="text-[10.5px] font-extrabold tracking-[0.14em]">{stamp.month}</span>
          <span className="text-[26px] font-extrabold leading-none tabular-nums">{stamp.day}</span>
          <span className="text-[10.5px] font-extrabold tracking-[0.14em] uppercase">
            {stamp.weekday}
          </span>
        </div>
        <div className="relative flex-1 min-w-0">
          <CatTag category={category} />
          <h2 className="mt-1.5 text-[22px] font-extrabold tracking-[-0.03em] leading-[1.05]">
            {name || "your event title"}
          </h2>
          <p className="mt-1 text-[13.5px] font-semibold">
            {timeLabel(new Date(date).toISOString())} · 📍 {venue || "venue"}
            {city ? `, ${city}` : ""}
          </p>
        </div>
      </div>

      <form onSubmit={onSubmit} className="mt-5 flex flex-col gap-4">
        <Slab className="p-5 flex flex-col gap-4">
          <Field label="name">
            <input
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="what's the plan?"
              required
              className={inputCls}
            />
          </Field>

          <Field label="category">
            <div className="flex flex-wrap gap-2">
              {(Object.keys(CATEGORIES) as Category[]).map((k) => {
                const m = CATEGORIES[k];
                const active = category === k;
                return (
                  <button
                    key={k}
                    type="button"
                    onClick={() => setCategory(k)}
                    className="inline-flex items-center gap-1.5 rounded-full border-[1.5px] border-ink px-3 py-1 text-[13px] font-extrabold shadow-chunky-sm transition-transform active:translate-x-[1px] active:translate-y-[1px]"
                    style={{
                      background: m.bg,
                      color: m.ink,
                      transform: active ? "translateY(-1px)" : undefined,
                      boxShadow: active ? "3px 3px 0 #0E100D" : undefined,
                    }}
                  >
                    <span aria-hidden>{m.emoji}</span>
                    <span className="lowercase">{m.label}</span>
                  </button>
                );
              })}
            </div>
          </Field>

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <Field label="date & time">
              <input
                type="datetime-local"
                value={date}
                onChange={(e) => setDate(e.target.value)}
                required
                className={inputCls}
              />
            </Field>
            <Field label="capacity (0 = unlimited)">
              <input
                type="number"
                min={0}
                value={capacity}
                onChange={(e) => setCapacity(Number(e.target.value))}
                className={inputCls}
              />
            </Field>
          </div>

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <Field label="venue">
              <input
                value={venue}
                onChange={(e) => setVenue(e.target.value)}
                placeholder="where?"
                className={inputCls}
              />
            </Field>
            <Field label="city">
              <input
                value={city}
                onChange={(e) => setCity(e.target.value)}
                className={inputCls}
              />
            </Field>
          </div>

          <Field label="description">
            <textarea
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              rows={4}
              placeholder="what do people need to know?"
              className={inputCls + " resize-y"}
            />
          </Field>
        </Slab>

        <div className="flex justify-end">
          <Chunky type="submit" variant="leaf" size="lg" disabled={submitting}>
            {submitting ? "posting…" : "post to #" + (guild?.channel ?? "outings")}
          </Chunky>
        </div>
      </form>
    </div>
  );
}

const inputCls =
  "w-full rounded-[10px] border-[1.5px] border-ink bg-paper px-3 py-2 text-[15px] font-medium shadow-chunky-sm focus:outline-none focus:shadow-chunky-md";

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="flex flex-col gap-1.5">
      <span className="text-[11px] font-extrabold tracking-[0.18em] text-mute uppercase">
        {label}
      </span>
      {children}
    </label>
  );
}
