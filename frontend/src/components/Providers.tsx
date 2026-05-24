"use client";

import { useEffect, useState, useSyncExternalStore, type ReactNode } from "react";
import { SWRConfig } from "swr";
import { localStorageProvider } from "@/lib/swrCache";
import { isDevModeActive } from "@/lib/devMode";
import { Toaster } from "@/components/ui/Toaster";

const MODE = process.env.NEXT_PUBLIC_API_MODE ?? "mock";

function isAutomatedBrowser() {
  return typeof navigator !== "undefined" && navigator.webdriver === true;
}

// Whether the in-browser mock backend (MSW) must boot before we render children.
// Dev mode only flips via a page reload, so a no-op subscribe is enough; the
// server snapshot knows only the build-time MODE.
const subscribeMock = () => () => {};
const needsMockClient = () => MODE === "mock" || isDevModeActive();
const needsMockServer = () => MODE === "mock";

export function Providers({ children }: { children: ReactNode }) {
  const needsMock = useSyncExternalStore(subscribeMock, needsMockClient, needsMockServer);
  const [mockReady, setMockReady] = useState(false);

  // `ready` is derived, not stored — the only state update is the async
  // setMockReady inside .then(), which isn't a synchronous set-state-in-effect.
  useEffect(() => {
    if (!needsMock) return;
    let cancelled = false;
    import("@/mocks/browser").then(({ worker }) =>
      worker.start({ onUnhandledRequest: "bypass" }).then(() => {
        if (!cancelled) setMockReady(true);
      }),
    );
    return () => {
      cancelled = true;
    };
  }, [needsMock]);

  const ready = !needsMock || mockReady;
  if (!ready) {
    return <div className="min-h-screen bg-paper" />;
  }

  return (
    <SWRConfig
      value={{
        provider: localStorageProvider,
        // Playwright sets navigator.webdriver. Background revalidations make
        // `networkidle` and click-then-navigate races flaky in e2e — disable
        // them under automation only; real users still get focus/reconnect
        // refresh.
        revalidateOnFocus: !isAutomatedBrowser(),
        revalidateOnReconnect: !isAutomatedBrowser(),
        dedupingInterval: 2000,
      }}
    >
      {children}
      <Toaster />
    </SWRConfig>
  );
}
