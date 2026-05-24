"use client";

import { useEffect } from "react";
import { initWebTracing } from "@/lib/otel/web";
import { registerTraceServiceWorker } from "@/lib/otel/traceServiceWorker";

/**
 * Boots browser RUM tracing once after hydration and registers the trace service
 * worker (which adds a traceparent to image/asset loads the page can't header).
 * Renders nothing. No-op unless NEXT_PUBLIC_OTEL_BROWSER_ENABLED is set.
 */
export function WebTracing() {
  useEffect(() => {
    initWebTracing();
    registerTraceServiceWorker();
  }, []);
  return null;
}
