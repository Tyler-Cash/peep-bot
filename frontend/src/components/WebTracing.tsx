"use client";

import { useEffect } from "react";
import { initWebTracing } from "@/lib/otel/web";

/**
 * Boots browser RUM tracing once after hydration. Renders nothing. No-op unless
 * NEXT_PUBLIC_OTEL_BROWSER_ENABLED is set (see {@link initWebTracing}).
 */
export function WebTracing() {
  useEffect(() => {
    initWebTracing();
  }, []);
  return null;
}
