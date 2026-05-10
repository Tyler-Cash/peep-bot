#!/usr/bin/env node
import { migrationLint } from "../dist/migrationLint.js";
import { lifecycleRegistry, discordInteractionMap, mswSpringDiff } from "../dist/domainScans.js";
import { springPropertyResolve, springPropertyList } from "../dist/springProps.js";

const sect = (label, body) => {
  console.log(`\n=== ${label} ===`);
  console.log(body);
};

sect("migration_lint", await migrationLint());
sect("lifecycle_registry", (await lifecycleRegistry()).slice(0, 2500));
sect("discord_interaction_map", (await discordInteractionMap()).slice(0, 2500));
sect("msw_spring_diff", (await mswSpringDiff()).slice(0, 2500));
sect(
  "spring_property dev.tylercash.cors.allowed-origins under local,nonprod",
  await springPropertyResolve({
    key: "dev.tylercash.cors.allowed-origins",
    profiles: "local,nonprod",
  }),
);
sect(
  "spring_properties_list prefix='spring.session' under local,nonprod",
  await springPropertyList({ profiles: "local,nonprod", prefix: "spring.session" }),
);
