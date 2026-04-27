"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { Peepo } from "@/components/Peepo";
import { DiscordGlyph } from "@/components/icons/DiscordGlyph";
import { LoadingOverlay } from "./LoadingOverlay";
import { useCurrentUser } from "@/lib/hooks";
import { activateDevMode } from "@/lib/devMode";

const MODE = process.env.NEXT_PUBLIC_API_MODE ?? "mock";
const API_BASE = process.env.NEXT_PUBLIC_API_BASE ?? "/api";
const SHOW_DEV_PANEL = MODE !== "live";

const features = [
  { emoji: "📅", title: "one thread", body: "all your plans live in #outings, off the main chat." },
  { emoji: "✅", title: "react to RSVP", body: "✅ 🤔 ❌ — peepbot tallies it for you." },
  { emoji: "🎞", title: "rewind", body: "end-of-month highlights. who showed up, what happened." },
];

export function LoginHero() {
  const router = useRouter();
  const [loading, setLoading] = useState(false);
  const { data: user } = useCurrentUser();

  useEffect(() => {
    if (user) router.replace("/");
  }, [user, router]);

  const onContinue = () => {
    if (loading) return;
    setLoading(true);
    if (MODE === "mock") {
      setTimeout(() => router.push("/"), 700);
      return;
    }
    window.location.href = `${API_BASE.replace(/\/api$/, "")}/api/oauth2/authorization/discord`;
  };

  const onDevLogin = () => {
    activateDevMode();
    window.location.reload();
  };

  return (
    <div className="relative min-h-screen overflow-hidden bg-paper font-sans text-ink">
      <div
        aria-hidden
        className="pointer-events-none absolute inset-0"
        style={{
          background: "radial-gradient(ellipse at 10% 0%, rgba(255,255,255,0.7), transparent 50%)",
        }}
      />
      <div
        aria-hidden
        className="pointer-events-none absolute right-[-8%] bottom-[-15%] w-[70%] opacity-[0.85]"
        style={{ aspectRatio: "1/1" }}
      >
        <Peepo size={760} />
      </div>

      <header className="relative z-10 flex items-center justify-between px-9 py-[22px]">
        <div className="flex items-center gap-2.5">
          <span className="inline-flex items-center justify-center w-10 h-10 rounded-[10px] bg-leaf border-[1.5px] border-ink shadow-chunky-sm">
            <Peepo size={28} />
          </span>
          <div className="flex flex-col leading-none">
            <span className="text-[18px] font-extrabold tracking-[-0.02em]">peepbot</span>
            <span className="mt-0.5 text-[10.5px] font-extrabold tracking-[0.18em] text-mute">
              PLANS, SORTED
            </span>
          </div>
        </div>
        <a
          href="#"
          onClick={(e) => e.preventDefault()}
          className="text-[13.5px] font-bold text-ink2"
        >
          already run a server?{" "}
          <span className="text-leafDk underline decoration-2 underline-offset-[3px]">
            add peepbot →
          </span>
        </a>
      </header>

      <main className="relative z-10 mx-auto max-w-[1200px] grid grid-cols-1 lg:grid-cols-[1.2fr_1fr] items-center gap-16 px-20 pb-[60px] pt-5">
        <div>
          <span
            className="inline-flex items-center gap-2 rounded-full border-[1.5px] border-ink bg-white px-3.5 py-1.5 text-[12.5px] font-extrabold shadow-chunky-sm"
            style={{ transform: "rotate(-1.5deg)" }}
          >
            <span className="w-[7px] h-[7px] rounded-full bg-leafDk" aria-hidden />
            a discord bot for irl friendships
          </span>

          <h1 className="mt-7 mb-5 font-extrabold text-ink leading-[0.95] tracking-[-0.04em]">
            <span className="block text-[64px] sm:text-[88px] lowercase">plans,</span>
            <span
              className="mt-2 inline-block rounded-[14px] border-[1.5px] border-ink bg-leaf px-[14px] pb-1 text-[56px] sm:text-[78px] shadow-chunky-lg lowercase"
              style={{ transform: "rotate(-1.2deg)" }}
            >
              sorted.
            </span>
          </h1>

          <p className="mb-9 text-[19px] text-ink2 leading-[1.5] font-medium max-w-[440px]">
            peepbot turns your group chat into actual hangouts. post an event in #outings,
            everyone RSVPs with a reaction, you show up.
          </p>

          <button
            onClick={onContinue}
            disabled={loading}
            className="inline-flex items-center gap-3 rounded-[14px] border-[1.5px] border-ink bg-discord text-white px-[26px] py-4 text-[17px] font-extrabold tracking-[-0.01em] shadow-chunky-md active:shadow-chunky-active active:translate-x-[2px] active:translate-y-[2px] transition-[box-shadow,transform] disabled:opacity-60 disabled:pointer-events-none"
          >
            <DiscordGlyph size={22} />
            continue with Discord
            <span aria-hidden className="ml-0.5 text-[18px]">→</span>
          </button>

          {SHOW_DEV_PANEL && (
            <div className="mt-5 inline-flex items-center gap-3 rounded-[10px] border border-dashed border-ink/30 bg-white/60 px-4 py-2.5">
              <span className="text-[11.5px] font-extrabold uppercase tracking-widest text-mute">
                dev
              </span>
              <button
                onClick={onDevLogin}
                className="text-[13px] font-bold text-leafDk underline underline-offset-2 decoration-2"
              >
                log in as Otis →
              </button>
            </div>
          )}

          <div className="mt-[46px] grid grid-cols-1 sm:grid-cols-3 gap-3.5 max-w-[580px]">
            {features.map((f) => (
              <div
                key={f.title}
                className="rounded-[12px] border-[1.5px] border-ink bg-white p-4 shadow-chunky"
              >
                <div className="text-[26px] leading-none mb-2" aria-hidden>
                  {f.emoji}
                </div>
                <div className="text-[14px] font-extrabold tracking-[-0.01em] lowercase">
                  {f.title}
                </div>
                <p className="mt-1 text-[12.5px] text-mute leading-[1.4]">{f.body}</p>
              </div>
            ))}
          </div>
        </div>

        <div className="hidden lg:block" />
      </main>

      {loading && <LoadingOverlay />}
    </div>
  );
}
