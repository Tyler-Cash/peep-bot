import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
import path from "path";

const alias = { "@": path.resolve(__dirname, "./src") };
const plugins = [react()];

export default defineConfig({
  plugins,
  test: {
    passWithNoTests: true,
    projects: [
      // ── node-environment tests (api, places, middleware, components) ──────
      {
        plugins,
        test: {
          name: "node",
          environment: "node",
          include: ["src/**/*.test.ts", "src/**/*.test.tsx"],
          exclude: [
            "node_modules",
            "dist",
            ".next",
            "e2e/**",
            // Render tests run under jsdom in the project below
            "src/__tests__/render/**",
          ],
          setupFiles: ["./src/__tests__/setup/dom.ts"],
        },
        resolve: { alias },
      },
      // ── jsdom render-harness tests ────────────────────────────────────────
      {
        plugins,
        test: {
          name: "render",
          environment: "jsdom",
          include: ["src/__tests__/render/**/*.test.tsx"],
          setupFiles: [
            "./src/__tests__/setup/msw.ts",
            "./src/__tests__/setup/dom.ts",
          ],
        },
        resolve: { alias },
      },
    ],
  },
});
