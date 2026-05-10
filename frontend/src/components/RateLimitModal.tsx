"use client";

import { useEffect, useState } from "react";
import { Chunky } from "@/components/ui/Chunky";
import { PeepoDead } from "@/components/Peepo";
import { subscribeRateLimited } from "@/lib/rateLimitNotifier";

const MAX_VISIBLE_SECONDS = 60;

export function RateLimitModal() {
  const [secondsLeft, setSecondsLeft] = useState<number | null>(null);

  useEffect(() => {
    return subscribeRateLimited((retrySec) => {
      const clamped = Math.min(Math.max(Math.ceil(retrySec), 1), MAX_VISIBLE_SECONDS);
      setSecondsLeft((current) => (current && current > clamped ? current : clamped));
    });
  }, []);

  useEffect(() => {
    if (secondsLeft == null) return;
    if (secondsLeft <= 0) {
      setSecondsLeft(null);
      return;
    }
    const t = setTimeout(() => setSecondsLeft((s) => (s == null ? null : s - 1)), 1000);
    return () => clearTimeout(t);
  }, [secondsLeft]);

  if (secondsLeft == null) return null;

  return (
    <div
      role="alertdialog"
      aria-modal="true"
      aria-labelledby="rate-limit-title"
      className="fixed inset-0 z-[60] flex items-center justify-center p-4"
    >
      <div className="absolute inset-0 bg-ink/40" onClick={() => setSecondsLeft(null)} />
      <div className="relative z-10 w-full max-w-[420px] rounded-hero border-[1.5px] border-ink bg-paper p-6 shadow-hero text-center">
        <div className="flex justify-center">
          <PeepoDead size={140} />
        </div>
        <h2
          id="rate-limit-title"
          className="mt-2 text-[24px] font-extrabold tracking-[-0.02em]"
        >
          woah, slow down there
        </h2>
        <p className="mt-2 text-[15px] text-ink2 leading-[1.6]">
          you&rsquo;ve hit the rate limit. peepo is taking a breather.
          <br />
          try again in <span className="font-extrabold">{secondsLeft}s</span>.
        </p>
        <div className="mt-5 flex justify-center">
          <Chunky variant="paper" size="sm" onClick={() => setSecondsLeft(null)}>
            ok
          </Chunky>
        </div>
      </div>
    </div>
  );
}
