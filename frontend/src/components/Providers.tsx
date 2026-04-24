"use client";

import { useEffect, useState, type ReactNode } from "react";
import { SWRConfig } from "swr";
import { localStorageProvider } from "@/lib/swrCache";

const MODE = process.env.NEXT_PUBLIC_API_MODE ?? "mock";

export function Providers({ children }: { children: ReactNode }) {
  const [ready, setReady] = useState(MODE !== "mock");

  useEffect(() => {
    if (MODE !== "mock") return;
    let cancelled = false;
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
        revalidateOnFocus: true,
        revalidateOnReconnect: true,
        dedupingInterval: 2000,
      }}
    >
      {children}
    </SWRConfig>
  );
}
