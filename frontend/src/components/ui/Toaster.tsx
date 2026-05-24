"use client";

import { useEffect } from "react";
import { Toaster as SonnerToaster } from "sonner";
import { subscribeRateLimited } from "@/lib/rateLimitNotifier";
import { showRateLimitToast } from "@/lib/toast";

/**
 * Global toast host. Renders unstyled Sonner toasts — every toast we emit supplies its own
 * neo-brutalist card via `toast.custom`, so Sonner only provides positioning and lifecycle.
 * Also bridges the rate-limit notifier (formerly a modal) into a countdown toast.
 */
export function Toaster() {
  useEffect(() => subscribeRateLimited((sec) => showRateLimitToast(sec)), []);

  return (
    <SonnerToaster
      position="bottom-right"
      toastOptions={{ unstyled: true }}
      gap={10}
    />
  );
}
