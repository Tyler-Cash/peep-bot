import { FlatCompat } from "@eslint/eslintrc";

const compat = new FlatCompat({ baseDirectory: import.meta.dirname });

export default [
  // Ignore openapi-ts generated files — they are machine-generated and should
  // not be linted or reformatted by hand.
  { ignores: ["src/lib/api/generated/**"] },
  ...compat.extends("next/core-web-vitals", "next/typescript"),
];
