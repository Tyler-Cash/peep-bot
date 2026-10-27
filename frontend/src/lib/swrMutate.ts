"use client";

import { useEffect } from "react";
import { mutate as defaultCacheMutate, useSWRConfig, type ScopedMutator } from "swr";

// The app's <SWRConfig> uses a custom cache `provider`, so the `mutate` exported by "swr" only
// touches SWR's default cache — not the one components read from — and cache updates made
// outside a component silently do nothing. <SwrMutateBridge/> registers the provider-bound
// mutator; module-level helpers (lib/hooks.ts) import `mutate` from here instead of "swr".
let bound: ScopedMutator = defaultCacheMutate;

export const mutate: ScopedMutator = ((...args: Parameters<ScopedMutator>) =>
  bound(...args)) as ScopedMutator;

export function SwrMutateBridge() {
  const { mutate: providerMutate } = useSWRConfig();
  useEffect(() => {
    bound = providerMutate;
    return () => {
      if (bound === providerMutate) bound = defaultCacheMutate;
    };
  }, [providerMutate]);
  return null;
}
