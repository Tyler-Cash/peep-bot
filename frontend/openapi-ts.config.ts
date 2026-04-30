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
  //
  // TODO(snowflake-as-string): Long-typed Discord IDs (guildId, channelId,
  // messageId, etc.) can exceed 2^53. Annotate the Java DTO fields with
  // @JsonFormat(shape = STRING) and regenerate so they become strings
  // everywhere. Track in a separate PR.
  plugins: ["@hey-api/typescript", "zod"],
});
