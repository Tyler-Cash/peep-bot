import nextCoreWebVitals from "eslint-config-next/core-web-vitals";
import nextTypescript from "eslint-config-next/typescript";

export default [
  // Ignore openapi-ts generated files and Next.js build artifacts — they are
  // machine-generated and should not be linted or reformatted by hand. The
  // legacy `next lint` command applied these defaults implicitly; the ESLint
  // CLI does not.
  {
    ignores: [
      "src/lib/api/generated/**",
      ".next/**",
      ".next-*/**",
      "out/**",
      "next-env.d.ts",
      "public/mockServiceWorker.js",
    ],
  },
  ...nextCoreWebVitals,
  ...nextTypescript,
  // eslint-plugin-react-hooks v6 (pulled in by eslint-config-next 16) added a
  // batch of new correctness rules that fire across the existing codebase.
  // Downgrade to warnings so they are surfaced without blocking CI on the
  // Next.js 16 upgrade; the underlying refactors are tracked separately.
  {
    rules: {
      "react-hooks/set-state-in-effect": "warn",
      "react-hooks/use-memo": "warn",
      "react-hooks/purity": "warn",
    },
  },
];
