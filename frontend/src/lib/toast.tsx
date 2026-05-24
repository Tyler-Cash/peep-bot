"use client";

import { useEffect, useState } from "react";
import { toast } from "sonner";
import { ApiError, describeError, UnauthorizedError } from "./api";
import { ErrorRef } from "@/components/ui/ErrorRef";
import { PeepoDead } from "@/components/Peepo";

const RATE_LIMIT_TOAST_ID = "rate-limit";
const MAX_VISIBLE_SECONDS = 60;

/**
 * Surface an error that has no natural inline home (a fire-and-forget mutation, a
 * background action). Maps the error through {@link describeError} and shows the friendly
 * message plus a copyable trace ref. No-ops on auth failures — those redirect to /login.
 */
export function toastError(e: unknown): void {
  if (e instanceof UnauthorizedError) return;
  // Rate limiting has its own canonical surface (the countdown toast); don't double up.
  if (e instanceof ApiError && e.status === 429) return;
  const { message, ref } = describeError(e);
  toast.custom(
    (id) => (
      <div className="w-[min(380px,90vw)] rounded-card border-[1.5px] border-ink bg-paper shadow-hero p-3.5">
        <div className="flex items-start justify-between gap-2">
          <p className="text-[14.5px] font-semibold leading-[1.4] text-ink">{message}</p>
          <button
            type="button"
            onClick={() => toast.dismiss(id)}
            aria-label="dismiss"
            className="-mt-0.5 shrink-0 border-0 bg-transparent text-[16px] font-extrabold leading-none text-mute hover:text-ink cursor-pointer"
          >
            ✕
          </button>
        </div>
        <ErrorRef info={ref} />
      </div>
    ),
    { duration: 8000 },
  );
}

/**
 * Replacement for the old rate-limit modal: a persistent toast with a live countdown.
 * Driven by `rateLimitNotifier` via {@link RateLimitToastHost}.
 */
export function showRateLimitToast(retryAfterSeconds: number): void {
  const seconds = Math.min(Math.max(Math.ceil(retryAfterSeconds), 1), MAX_VISIBLE_SECONDS);
  toast.custom((id) => <RateLimitToastBody id={id} initialSeconds={seconds} />, {
    id: RATE_LIMIT_TOAST_ID,
    duration: Infinity,
  });
}

function RateLimitToastBody({
  id,
  initialSeconds,
}: {
  id: string | number;
  initialSeconds: number;
}) {
  const [secondsLeft, setSecondsLeft] = useState(initialSeconds);

  useEffect(() => {
    if (secondsLeft <= 0) {
      toast.dismiss(id);
      return;
    }
    const t = setTimeout(() => setSecondsLeft((s) => s - 1), 1000);
    return () => clearTimeout(t);
  }, [secondsLeft, id]);

  return (
    <div className="flex w-[min(380px,90vw)] items-center gap-3 rounded-card border-[1.5px] border-ink bg-paper p-3.5 shadow-hero">
      <PeepoDead size={56} />
      <div className="min-w-0">
        <p className="text-[15px] font-extrabold tracking-[-0.01em] text-ink">
          woah, slow down there
        </p>
        <p className="mt-0.5 text-[13px] text-ink2 leading-[1.4]">
          you&rsquo;ve hit the rate limit. try again in{" "}
          <span className="font-extrabold">{secondsLeft}s</span>.
        </p>
      </div>
    </div>
  );
}
