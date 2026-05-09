"use client";

import { useEffect, useState, type ReactNode } from "react";
import { SWRConfig } from "swr";
import { localStorageProvider } from "@/lib/swrCache";
import { isDevModeActive } from "@/lib/devMode";

const MODE = process.env.NEXT_PUBLIC_API_MODE ?? "mock";

function isAutomatedBrowser() {
  return typeof navigator !== "undefined" && navigator.webdriver === true;
}

export function Providers({ children }: { children: ReactNode }) {
  const [ready, setReady] = useState(false);

  useEffect(() => {
    let cancelled = false;
    const useMock = MODE === "mock" || isDevModeActive();
    if (!useMock) {
      setReady(true);
      return;
    }
    import("@/mocks/browser").then(({ worker }) =>
      worker.start({ onUnhandledRequest: "bypass" }).then(() => {
        if (!cancelled) setReady(true);
      }),
    );
    return () => {
      cancelled = true;
    };
  }, []);

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
    </SWRConfig>
  );
}
