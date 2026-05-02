"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import Image from "next/image";
import { useRouter } from "next/navigation";
import { mutate } from "swr";
import { DiscordGlyph } from "@/components/icons/DiscordGlyph";
import { LoadingOverlay } from "./LoadingOverlay";
import { useCurrentUser } from "@/lib/hooks";
import { activateDevMode } from "@/lib/devMode";
import { isAuthLoopTripped, noteAuthSuccess } from "@/lib/authLoopGuard";

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
  const popupRef = useRef<Window | null>(null);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  useEffect(() => {
    // If we've bounced between / and /login too many times, stay put — the
    // cached `user` here is almost certainly stale. The user can click the
    // login button to start a fresh OAuth flow, which will reset the guard.
    if (user && !isAuthLoopTripped()) router.replace("/");
  }, [user, router]);

  useEffect(() => {
    return () => {
      if (pollRef.current) clearInterval(pollRef.current);
      if (popupRef.current && !popupRef.current.closed) popupRef.current.close();
    };
  }, []);

  const onContinue = () => {
    if (loading) return;
    setLoading(true);
    // User is explicitly trying to log in — clear any prior bounce-loop state
    // so a successful auth ends at /, not stuck on /login.
    noteAuthSuccess();
    if (MODE === "mock") {
      // Clear the mock logout flag so the user is logged in
      window.localStorage.removeItem("mock-auth-logged-out");
      setTimeout(() => router.push("/"), 700);
      return;
    }
    const oauthUrl = `${API_BASE.replace(/\/api$/, "")}/api/oauth2/authorization/discord`;
    const w = 520;
    const h = 720;
    const left = window.screenX + (window.outerWidth - w) / 2;
    const top = window.screenY + (window.outerHeight - h) / 2;
    const popup = window.open(
      oauthUrl,
      "peepbot-discord-login",
      `width=${w},height=${h},left=${left},top=${top},menubar=no,toolbar=no,location=no,status=no`,
    );
    if (!popup) {
      // Popup blocked — fall back to full-page redirect.
      window.location.href = oauthUrl;
      return;
    }
    popupRef.current = popup;
    if (pollRef.current) clearInterval(pollRef.current);
    pollRef.current = setInterval(async () => {
      if (popup.closed) {
        if (pollRef.current) clearInterval(pollRef.current);
        pollRef.current = null;
        const refreshed = await mutate("/auth/is-logged-in");
        if (refreshed) {
          router.replace("/");
        } else {
          setLoading(false);
        }
        return;
      }
      // Once the popup returns to our origin, OAuth is done — close it.
      try {
        if (popup.location.origin === window.location.origin) {
          popup.close();
        }
      } catch {
        // Cross-origin during Discord auth; ignore.
      }
    }, 500);
  };

  const onDevLogin = () => {
    activateDevMode();
    window.location.reload();
  };

  // Stable per-peepo tilt/lift so the lineup doesn't reshuffle on every render.
  const lineup = useMemo(
    () => Array.from({ length: 14 }, (_, i) => ({ tilt: ((i * 37) % 9) - 4, lift: (i * 13) % 6 })),
    [],
  );

  return (
    <div className="relative min-h-screen overflow-hidden bg-paper font-sans text-ink">
      <div
        aria-hidden
        className="pointer-events-none absolute inset-0"
        style={{
          background: "radial-gradient(ellipse at 10% 0%, rgba(255,255,255,0.7), transparent 50%)",
        }}
      />
      {/* Background — large cheering peepo, vertically centered so it doesn't
          jump as viewport height changes. */}
      <div
        aria-hidden
        className="pointer-events-none fixed right-[-6%] top-1/2 -translate-y-1/2 w-[60%] sm:w-[48%] max-w-[680px] opacity-[0.22] select-none"
      >
        <Image
          src="/peepos/peepo-cheer.png"
          alt=""
          width={112}
          height={112}
          className="w-full h-auto"
          style={{ transform: "rotate(-4deg)" }}
        />
      </div>
      {/* Foreground lineup — a row of peepoHappys, vertically centered against
          the viewport (fixed) so the row never jumps as content height shifts
          across breakpoints. */}
      <div
        aria-hidden
        className="pointer-events-none fixed inset-x-0 top-1/2 -translate-y-1/2 flex items-end justify-center gap-1 sm:gap-2 opacity-[0.55] select-none"
      >
        {lineup.map((p, i) => (
          <Image
            key={i}
            src="/peepos/peepo.png"
            alt=""
            width={112}
            height={112}
            className="h-[110px] sm:h-[150px] w-auto shrink-0"
            style={{ transform: `translateY(${p.lift}px) rotate(${p.tilt}deg)` }}
          />
        ))}
      </div>

      <header className="relative z-10 flex items-center justify-between gap-3 px-4 sm:px-9 py-[18px] sm:py-[22px]">
        <div className="flex items-center gap-2.5">
          <span className="inline-flex items-center justify-center w-10 h-10 rounded-chip bg-leaf border-[1.5px] border-ink shadow-rest overflow-hidden">
            <Image src="/peepos/peepo.png" alt="" aria-hidden width={32} height={32} className="w-[32px] h-[32px] object-contain" priority />
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
          className="hidden sm:block text-[13.5px] font-bold text-ink2"
        >
          already run a server?{" "}
          <span className="text-leafDk underline decoration-2 underline-offset-[3px]">
            add peepbot →
          </span>
        </a>
      </header>

      <main className="relative z-10 mx-auto max-w-[1200px] grid grid-cols-1 lg:grid-cols-[1.2fr_1fr] items-center gap-16 px-5 sm:px-12 lg:px-20 pb-[60px] pt-5">
        <div>
          <span
            className="inline-flex items-center gap-2 rounded-chip border-[1.5px] border-ink bg-white px-3.5 py-1.5 text-[12.5px] font-extrabold shadow-rest"
            style={{ transform: "rotate(-1.5deg)" }}
          >
            <span className="w-[7px] h-[7px] rounded-full bg-leafDk" aria-hidden />
            a discord bot for irl friendships
          </span>

          <h1 className="mt-7 mb-5 font-extrabold text-ink leading-[0.95] tracking-[-0.04em]">
            <span className="block text-[64px] sm:text-[88px] lowercase">plans,</span>
            <span
              className="mt-2 inline-block rounded-card border-[1.5px] border-ink bg-leaf px-[14px] pb-1 text-[56px] sm:text-[78px] shadow-hero lowercase"
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
            className="inline-flex items-center gap-3 rounded-card border-[1.5px] border-ink bg-discord text-white px-[26px] py-4 text-[17px] font-extrabold tracking-[-0.01em] shadow-rest active:shadow-press active:translate-x-[2px] active:translate-y-[2px] transition-[box-shadow,transform] disabled:opacity-60 disabled:pointer-events-none"
          >
            <DiscordGlyph size={22} />
            continue with Discord
            <span aria-hidden className="ml-0.5 text-[18px]">→</span>
          </button>

          {SHOW_DEV_PANEL && (
            <div className="mt-5 inline-flex items-center gap-3 rounded-chip border border-dashed border-ink/30 bg-white/60 px-4 py-2.5">
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
                className="rounded-card border-[1.5px] border-ink bg-white p-4 shadow-rest"
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
