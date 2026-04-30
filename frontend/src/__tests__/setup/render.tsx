import { render, type RenderOptions } from "@testing-library/react";
import type { ReactElement } from "react";
import { SWRConfig } from "swr";
import { vi } from "vitest";

/**
 * Render a UI tree with the same provider stack the app uses, but with a
 * disposable SWR cache so tests don't leak data between cases.
 */
export function renderWithProviders(ui: ReactElement, options?: RenderOptions) {
  return render(
    <SWRConfig
      value={{
        provider: () => new Map(),
        dedupingInterval: 0,
        revalidateOnFocus: false,
        revalidateOnReconnect: false,
      }}
    >
      {ui}
    </SWRConfig>,
    options,
  );
}

/**
 * Capture console.error calls during a test so the test can assert no React
 * warnings or unhandled errors fired. Returns a restore function.
 */
export function trapConsoleError(): { errors: unknown[][]; restore: () => void } {
  const errors: unknown[][] = [];
  const spy = vi.spyOn(console, "error").mockImplementation((...args) => {
    errors.push(args);
  });
  return { errors, restore: () => spy.mockRestore() };
}
