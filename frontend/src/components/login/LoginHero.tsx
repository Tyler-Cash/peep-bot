"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { Peepo } from "@/components/Peepo";
import { Chunky } from "@/components/ui/Chunky";
import { DiscordGlyph } from "@/components/icons/DiscordGlyph";
import { LoadingOverlay } from "./LoadingOverlay";

const MODE = process.env.NEXT_PUBLIC_API_MODE ?? "mock";
const API_BASE = process.env.NEXT_PUBLIC_API_BASE ?? "/api";

const features = [
  { emoji: "📅", title: "one thread", body: "all your plans live in #outings, off the main chat." },
  { emoji: "✅", title: "react to RSVP", body: "✅ 🤔 ❌ — peepbot tallies it for you." },
  { emoji: "🎞", title: "rewind", body: "end-of-month highlights. who showed up, what happened." },
];

export function LoginHero() {
  const router = useRouter();
  const [loading, setLoading] = useState(false);

  const onContinue = () => {
    setLoading(true);
    if (MODE === "mock") {
      setTimeout(() => router.push("/"), 700);
      return;
    }
    window.location.href = `${API_BASE.replace(/\/api$/, "")}/api/oauth2/authorization/discord`;
  };

  return (
    <div className="relative min-h-screen overflow-hidden bg-paper">
      <div
        aria-hidden
        className="pointer-events-none absolute inset-0"
        style={{
          background:
            "radial-gradient(ellipse at 10% 0%, rgba(255,255,255,0.7), transparent 50%)",
        }}
      />
      <div
        aria-hidden
        className="pointer-events-none absolute right-[-8%] bottom-[-15%] opacity-[0.85]"
      >
        <Peepo size={760} />
      </div>

      <div className="relative mx-auto max-w-[1200px] px-6 py-6">
        <header className="flex items-center justify-between">
          <div className="flex items-center gap-2.5">
            <span className="inline-flex items-center justify-center w-10 h-10 rounded-[10px] bg-leaf border-[1.5px] border-ink shadow-chunky-sm">
              <Peepo size={26} />
            </span>
            <div className="flex flex-col leading-none">
              <span className="text-[19px] font-extrabold tracking-[-0.02em]">peepbot</span>
              <span className="text-[10.5px] font-extrabold tracking-[0.2em] text-mute mt-0.5">
                PLANS, SORTED
              </span>
            </div>
          </div>
          <a href="#" className="text-[13px] font-semibold text-mute hover:text-ink">
            already run a server? add peepbot →
          </a>
        </header>

        <div className="mt-16 grid grid-cols-1 lg:grid-cols-[1.2fr_1fr] gap-8 items-center">
          <div>
            <span
              className="inline-flex items-center gap-2 rounded-full border-[1.5px] border-ink bg-paper2 px-3 py-1 text-[12px] font-extrabold shadow-chunky-sm"
              style={{ transform: "rotate(-1deg)" }}
            >
              <span className="w-1.5 h-1.5 rounded-full bg-leaf" /> a discord bot for irl friendships
            </span>

            <h1 className="mt-5 font-extrabold text-ink leading-[0.95] tracking-[-0.04em]">
              <span className="block text-[64px] sm:text-[88px] lowercase">plans,</span>
              <span
                className="inline-block mt-2 rounded-[14px] border-[1.5px] border-ink bg-leaf px-4 py-1 text-[56px] sm:text-[78px] shadow-chunky-lg lowercase"
                style={{ transform: "rotate(-1.2deg)" }}
              >
                sorted.
              </span>
            </h1>

            <p className="mt-5 text-[19px] text-ink2 leading-[1.5] max-w-[440px]">
              peepbot turns your group chat into actual hangouts. post an event in #outings,
              everyone RSVPs with a reaction, you show up.
            </p>

            <div className="mt-6">
              <Chunky variant="discord" size="lg" onClick={onContinue} disabled={loading}>
                <DiscordGlyph size={20} />
                Continue with Discord
              </Chunky>
            </div>

            <div className="mt-8 grid grid-cols-1 sm:grid-cols-3 gap-3 max-w-[560px]">
              {features.map((f) => (
                <div
                  key={f.title}
                  className="rounded-[12px] border-[1.5px] border-ink bg-paper2 p-3 shadow-chunky-sm"
                >
                  <div className="text-[22px]" aria-hidden>
                    {f.emoji}
                  </div>
                  <div className="mt-1 text-[14px] font-extrabold lowercase">{f.title}</div>
                  <p className="mt-0.5 text-[12.5px] text-mute leading-snug">{f.body}</p>
                </div>
              ))}
            </div>
          </div>
        </div>
      </div>

      {loading && <LoadingOverlay />}
    </div>
  );
}
