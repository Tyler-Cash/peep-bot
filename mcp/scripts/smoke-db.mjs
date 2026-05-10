#!/usr/bin/env node
import { dbInfo, dbQuery } from "../dist/dbQuery.js";

console.log("=== db_info ===");
console.log(await dbInfo());
console.log();

console.log("=== db_query: SELECT count(*) FROM guild ===");
console.log(await dbQuery({ sql: "SELECT count(*) FROM guild" }));
console.log();

console.log("=== db_query: list tables ===");
console.log(
  await dbQuery({
    sql: "SELECT table_name FROM information_schema.tables WHERE table_schema='public' ORDER BY table_name",
    maxRows: 30,
  }),
);
console.log();

console.log("=== db_query: refused write ===");
console.log(await dbQuery({ sql: "DELETE FROM guild" }));
