import { defineConfig } from "@hey-api/openapi-ts";

export default defineConfig({
  input: "../backend/openapi.json",
  output: {
    path: "src/lib/api/generated",
  },
  // The zod plugin emits z.coerce.bigint() for int64 (Long) fields by default.
  // @hey-api/openapi-ts has no built-in config flag to suppress this — a
  // post-codegen sed step in the `codegen` npm script rewrites those to
  // z.number() so the zod schema type matches the TypeScript type (number).
  // Discord snowflakes cross the wire as strings to avoid the JS Number
  // precision ceiling; non-snowflake Long fields stay numeric.
  plugins: ["@hey-api/typescript", "zod"],
});
